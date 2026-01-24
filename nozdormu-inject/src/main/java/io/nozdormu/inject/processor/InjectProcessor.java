package io.nozdormu.inject.processor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.nodeTypes.modifiers.NodeWithStaticModifier;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.UnknownType;
import com.github.javaparser.ast.type.VoidType;
import com.google.auto.service.AutoService;
import com.google.common.collect.Streams;
import io.nozdormu.common.ProcessorManager;
import io.nozdormu.spi.context.*;
import io.nozdormu.spi.error.InjectionProcessException;
import io.nozdormu.spi.event.ScopeEventAsyncObserver;
import io.nozdormu.spi.event.ScopeEventObserver;
import jakarta.annotation.Generated;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.*;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.*;
import jakarta.transaction.TransactionScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static io.nozdormu.spi.error.InjectionProcessErrorType.*;

@SupportedAnnotationTypes({
        "jakarta.inject.Singleton",
        "jakarta.enterprise.context.Dependent",
        "jakarta.enterprise.context.ApplicationScoped",
        "jakarta.enterprise.context.RequestScoped",
        "jakarta.enterprise.context.SessionScoped",
        "jakarta.transaction.TransactionScoped"
})
@AutoService(Processor.class)
public class InjectProcessor extends AbstractProcessor {

    private static final Logger logger = LoggerFactory.getLogger(InjectProcessor.class);

    private final Set<ComponentProxyProcessor> componentProxyProcessors = new HashSet<>();
    private ProcessorManager processorManager;
    private final Map<String, CompilationUnit> compilationUnitListMap = new ConcurrentHashMap<>();
    private final Map<String, CompilationUnit> proxyCompilationUnitMap = new ConcurrentHashMap<>();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        ServiceLoader<ComponentProxyProcessor> loader = ServiceLoader.load(ComponentProxyProcessor.class, InjectProcessor.class.getClassLoader());
        loader.forEach(componentProxyProcessors::add);
        this.processorManager = new ProcessorManager(processingEnv, InjectProcessor.class.getClassLoader());
        for (ComponentProxyProcessor componentProxyProcessor : this.componentProxyProcessors) {
            logger.info("{} init", componentProxyProcessor.getClass().getName());
            componentProxyProcessor.init(processorManager);
        }
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            String rootPackageName = processorManager.getRootPackageName();
            CompilationUnit proxySuppliersCompilationUnit = buildProxySuppliers(rootPackageName, new ArrayList<>(compilationUnitListMap.values()));
            processorManager.writeToFiler(proxySuppliersCompilationUnit);
            processorManager.registerSpi("io.nozdormu.spi.context.BeanSuppliers", rootPackageName + ".ProxySuppliers");
            processorManager.flushSpiFiles();
        }
        if (annotations.isEmpty()) {
            return false;
        }
        Set<? extends Element> singletonSet = roundEnv.getElementsAnnotatedWith(Singleton.class);
        Set<? extends Element> dependentSet = roundEnv.getElementsAnnotatedWith(Dependent.class);
        Set<? extends Element> applicationScopedSet = roundEnv.getElementsAnnotatedWith(ApplicationScoped.class);
        Set<? extends Element> requestScopedSet = roundEnv.getElementsAnnotatedWith(RequestScoped.class);
        Set<? extends Element> sessionScopedSet = roundEnv.getElementsAnnotatedWith(SessionScoped.class);
        Set<? extends Element> transactionScopedSet = roundEnv.getElementsAnnotatedWith(TransactionScoped.class);

        List<TypeElement> typeElements = Streams
                .concat(
                        singletonSet.stream(),
                        dependentSet.stream(),
                        applicationScopedSet.stream(),
                        requestScopedSet.stream(),
                        sessionScopedSet.stream(),
                        transactionScopedSet.stream()
                )
                .filter(element -> element.getAnnotation(Generated.class) == null)
                .filter(element -> element.getKind().isClass())
                .map(element -> (TypeElement) element)
                .collect(Collectors.toList());

        if (typeElements.isEmpty()) {
            return false;
        }

        processorManager.setRoundEnv(roundEnv);

        componentProxyProcessors.forEach(componentProxyProcessor -> {
            logger.info("{} in process", componentProxyProcessor.getClass().getName());
            componentProxyProcessor.inProcess();
        });

        typeElements.forEach(typeElement ->
                processorManager.getCompilationUnit(typeElement).ifPresent(componentCompilationUnit -> {
                    compilationUnitListMap.put(typeElement.getQualifiedName().toString(), componentCompilationUnit);
                    if (match(typeElement)) {
                        CompilationUnit proxyCompilationUnit = buildComponentProxy(typeElement);
                        proxyCompilationUnitMap.put(typeElement.getQualifiedName().toString(), proxyCompilationUnit);
                        processorManager.writeToFiler(proxyCompilationUnit);
                    }
                })
        );

        return false;
    }

    private CompilationUnit buildComponentProxy(TypeElement typeElement) {
        return processorManager.getCompilationUnit(typeElement)
                .map(compilationUnit -> buildComponentProxy(typeElement, compilationUnit))
                .orElseThrow(() -> new InjectionProcessException(CANNOT_GET_COMPILATION_UNIT.bind(typeElement.getQualifiedName().toString())));
    }

    private boolean match(TypeElement typeElement) {
        boolean hasInject = typeElement.getEnclosedElements().stream()
                .filter(element ->
                        element.getKind().equals(ElementKind.FIELD) ||
                                element.getKind().equals(ElementKind.METHOD) ||
                                element.getKind().equals(ElementKind.CONSTRUCTOR)
                )
                .anyMatch(element -> hasAnnotation(element, Inject.class.getName()));

        if (hasInject) {
            return true;
        }

        boolean hasObserver = typeElement.getEnclosedElements().stream()
                .filter(element -> element.getKind().equals(ElementKind.METHOD))
                .map(element -> (ExecutableElement) element)
                .flatMap(executableElement -> executableElement.getParameters().stream())
                .anyMatch(parameter ->
                        hasAnnotation(parameter, Observes.class.getName()) ||
                                hasAnnotation(parameter, ObservesAsync.class.getName())
                );

        if (hasObserver) {
            return true;
        }

        return componentProxyProcessors.stream()
                .anyMatch(componentProxyProcessor -> componentProxyProcessor.match(typeElement));
    }

    private boolean hasAnnotation(Element element, String annotationQualifiedName) {
        return element.getAnnotationMirrors().stream()
                .map(annotationMirror -> annotationMirror.getAnnotationType().asElement())
                .filter(TypeElement.class::isInstance)
                .map(type -> ((TypeElement) type).getQualifiedName().toString())
                .anyMatch(annotationQualifiedName::equals);
    }

    private CompilationUnit buildComponentProxy(TypeElement typeElement, CompilationUnit componentCompilationUnit) {
        return buildComponentProxy(
                typeElement,
                componentCompilationUnit,
                processorManager.getPublicClassOrInterfaceDeclarationOrError(componentCompilationUnit)
        );
    }

    private CompilationUnit buildComponentProxy(TypeElement typeElement, CompilationUnit componentCompilationUnit, ClassOrInterfaceDeclaration componentClassDeclaration) {
        String qualifiedName = processorManager.getQualifiedName(componentClassDeclaration);

        logger.info("{} proxy class build start", qualifiedName);

        ClassOrInterfaceDeclaration proxyClassDeclaration = new ClassOrInterfaceDeclaration()
                .addModifier(Modifier.Keyword.PUBLIC)
                .addExtendedType(componentClassDeclaration.getNameAsString())
                .setName(componentClassDeclaration.getNameAsString() + "_Proxy")
                .addAnnotation(new NormalAnnotationExpr().addPair("value", new StringLiteralExpr(getClass().getName())).setName(Generated.class.getSimpleName()));

        CompilationUnit proxyCompilationUnit = new CompilationUnit()
                .addType(proxyClassDeclaration)
                .addImport(Generated.class);

        componentCompilationUnit.getPackageDeclaration()
                .ifPresent(proxyCompilationUnit::setPackageDeclaration);

        processorManager.importAllClassOrInterfaceType(proxyClassDeclaration, componentClassDeclaration);

        List<FieldDeclaration> privateFieldDeclarationList = componentClassDeclaration.getFields().stream()
                .filter(fieldDeclaration -> fieldDeclaration.hasModifier(Modifier.Keyword.PRIVATE))
                .filter(fieldDeclaration -> !fieldDeclaration.hasModifier(Modifier.Keyword.STATIC))
                .filter(fieldDeclaration ->
                        componentClassDeclaration.getConstructors().stream()
                                .flatMap(constructorDeclaration -> constructorDeclaration.getParameters().stream())
                                .map(NodeWithSimpleName::getNameAsString)
                                .anyMatch(name -> fieldDeclaration.getVariable(0).getNameAsString().equals(name))
                )
                .map(FieldDeclaration::clone)
                .collect(Collectors.toList());

        privateFieldDeclarationList.forEach(proxyClassDeclaration::addMember);

        componentClassDeclaration.getConstructors()
                .forEach(constructorDeclaration -> {
                    ConstructorDeclaration proxyConstructorDeclaration = proxyClassDeclaration
                            .addConstructor(Modifier.Keyword.PUBLIC)
                            .setAnnotations(constructorDeclaration.getAnnotations())
                            .setParameters(constructorDeclaration.getParameters());

                    BlockStmt blockStmt = proxyConstructorDeclaration
                            .createBody()
                            .addStatement(
                                    new MethodCallExpr()
                                            .setName(new SuperExpr().toString())
                                            .setArguments(
                                                    constructorDeclaration.getParameters().stream()
                                                            .map(NodeWithSimpleName::getNameAsExpression)
                                                            .collect(Collectors.toCollection(NodeList::new))
                                            )
                            );

                    privateFieldDeclarationList.stream()
                            .filter(fieldDeclaration ->
                                    constructorDeclaration.getParameters().stream()
                                            .map(NodeWithSimpleName::getNameAsString)
                                            .anyMatch(name -> fieldDeclaration.getVariable(0).getNameAsString().equals(name))
                            )
                            .forEach(fieldDeclaration ->
                                    blockStmt.addStatement(
                                            new AssignExpr()
                                                    .setTarget(new FieldAccessExpr().setName(fieldDeclaration.getVariable(0).getNameAsString()).setScope(new ThisExpr()))
                                                    .setValue(fieldDeclaration.getVariable(0).getNameAsExpression())
                                                    .setOperator(AssignExpr.Operator.ASSIGN)
                                    )
                            );

                    componentClassDeclaration.getMethods().stream()
                            .filter(methodDeclaration ->
                                    methodDeclaration.isAnnotationPresent(Inject.class) ||
                                            processorManager.isInjectFieldSetter(methodDeclaration)
                            )
                            .forEach(methodDeclaration -> {
                                        methodDeclaration.getParameters().forEach(proxyConstructorDeclaration::addParameter);
                                        blockStmt
                                                .addStatement(
                                                        new MethodCallExpr()
                                                                .setName(methodDeclaration.getName())
                                                                .setArguments(
                                                                        methodDeclaration.getParameters().stream()
                                                                                .map(NodeWithSimpleName::getNameAsExpression)
                                                                                .collect(Collectors.toCollection(NodeList::new))
                                                                )
                                                );
                                    }
                            );
                });

        componentClassDeclaration.getAnnotations().stream()
                .filter(annotationExpr ->
                        proxyClassDeclaration.getAnnotations().stream()
                                .noneMatch(exist -> exist.getNameAsString().equals(annotationExpr.getNameAsString()))
                )
                .filter(annotationExpr -> {
                    String scopedAnnotationName = processorManager.getQualifiedName(annotationExpr);
                    return !scopedAnnotationName.equals(Singleton.class.getName()) &&
                            !scopedAnnotationName.equals(Dependent.class.getName()) &&
                            !scopedAnnotationName.equals(ApplicationScoped.class.getName()) &&
                            !scopedAnnotationName.equals(SessionScoped.class.getName()) &&
                            !scopedAnnotationName.equals(RequestScoped.class.getName()) &&
                            !scopedAnnotationName.equals(TransactionScoped.class.getName());
                })
                .forEach(proxyClassDeclaration::addAnnotation);

        List<MethodDeclaration> observesMethods = componentClassDeclaration.getMethods().stream()
                .filter(methodDeclaration ->
                        methodDeclaration.getParameters().stream()
                                .anyMatch(parameter -> parameter.isAnnotationPresent(Observes.class))
                )
                .collect(Collectors.toList());

        if (!observesMethods.isEmpty()) {
            proxyCompilationUnit.addImport(ScopeEventObserver.class);

            observesMethods.forEach(methodDeclaration -> {
                ClassOrInterfaceDeclaration observerClassDeclaration = new ClassOrInterfaceDeclaration()
                        .addModifier(Modifier.Keyword.PUBLIC, Modifier.Keyword.FINAL)
                        .addImplementedType(ScopeEventObserver.class.getSimpleName())
                        .setName(componentClassDeclaration.getNameAsString() + "_" + methodDeclaration.getNameAsString() + "_Observer");

                methodDeclaration.getParameters().stream()
                        .filter(parameter -> parameter.isAnnotationPresent(Observes.class))
                        .findFirst()
                        .flatMap(parameter -> parameter.getAnnotationByClass(Initialized.class))
                        .ifPresent(observerClassDeclaration::addAnnotation);

                methodDeclaration.getParameters().stream()
                        .filter(parameter -> parameter.isAnnotationPresent(Observes.class))
                        .findFirst()
                        .flatMap(parameter -> parameter.getAnnotationByClass(BeforeDestroyed.class))
                        .ifPresent(observerClassDeclaration::addAnnotation);

                methodDeclaration.getParameters().stream()
                        .filter(parameter -> parameter.isAnnotationPresent(Observes.class))
                        .findFirst()
                        .flatMap(parameter -> parameter.getAnnotationByClass(Destroyed.class))
                        .ifPresent(observerClassDeclaration::addAnnotation);

                methodDeclaration.getParameters().stream()
                        .filter(parameter -> parameter.isAnnotationPresent(Observes.class))
                        .findFirst()
                        .flatMap(parameter -> parameter.getAnnotationByClass(Priority.class))
                        .ifPresent(observerClassDeclaration::addAnnotation);

                if (processorManager.getQualifiedName(methodDeclaration.getType()).equals(Mono.class.getName())) {
                    proxyCompilationUnit.addImport(Void.class);

                    MethodDeclaration onEvent = new MethodDeclaration()
                            .setName("onEventAsync")
                            .setModifiers(Modifier.Keyword.PUBLIC)
                            .addParameter(new Parameter().setName(methodDeclaration.getParameter(0).getName()).setType(Object.class.getSimpleName()))
                            .setType(
                                    new ClassOrInterfaceType()
                                            .setName(Mono.class.getSimpleName())
                                            .setTypeArguments(new ClassOrInterfaceType().setName(Void.class.getSimpleName()))
                            )
                            .addAnnotation(Override.class);

                    onEvent.setBody(
                            new BlockStmt()
                                    .addStatement(
                                            new ReturnStmt(
                                                    new MethodCallExpr()
                                                            .setName(methodDeclaration.getName())
                                                            .addArgument(methodDeclaration.getParameter(0).getNameAsExpression())
                                            )
                                    )
                    );

                    observerClassDeclaration.addMember(onEvent);
                } else {
                    MethodDeclaration onEvent = new MethodDeclaration()
                            .setName("onEvent")
                            .setModifiers(Modifier.Keyword.PUBLIC)
                            .addParameter(new Parameter().setName(methodDeclaration.getParameter(0).getName()).setType(Object.class.getSimpleName()))
                            .setType(new VoidType())
                            .addAnnotation(Override.class);

                    onEvent.setBody(
                            new BlockStmt()
                                    .addStatement(
                                            new MethodCallExpr()
                                                    .setName(methodDeclaration.getName())
                                                    .addArgument(methodDeclaration.getParameter(0).getNameAsExpression())
                                    )
                    );

                    observerClassDeclaration.addMember(onEvent);
                }
                proxyClassDeclaration.addMember(observerClassDeclaration);
            });
        }

        List<MethodDeclaration> observesAsyncMethods = componentClassDeclaration.getMethods().stream()
                .filter(methodDeclaration ->
                        methodDeclaration.getParameters().stream()
                                .anyMatch(parameter -> parameter.isAnnotationPresent(ObservesAsync.class))
                )
                .collect(Collectors.toList());

        if (!observesAsyncMethods.isEmpty()) {
            proxyCompilationUnit.addImport(ScopeEventAsyncObserver.class);

            observesAsyncMethods.forEach(methodDeclaration -> {
                ClassOrInterfaceDeclaration observerClassDeclaration = new ClassOrInterfaceDeclaration()
                        .addModifier(Modifier.Keyword.PUBLIC, Modifier.Keyword.FINAL)
                        .addImplementedType(ScopeEventAsyncObserver.class.getSimpleName())
                        .setName(componentClassDeclaration.getNameAsString() + "_" + methodDeclaration.getNameAsString() + "_Observer");

                methodDeclaration.getParameters().stream()
                        .filter(parameter -> parameter.isAnnotationPresent(ObservesAsync.class))
                        .findFirst()
                        .flatMap(parameter -> parameter.getAnnotationByClass(Initialized.class))
                        .ifPresent(observerClassDeclaration::addAnnotation);

                methodDeclaration.getParameters().stream()
                        .filter(parameter -> parameter.isAnnotationPresent(ObservesAsync.class))
                        .findFirst()
                        .flatMap(parameter -> parameter.getAnnotationByClass(BeforeDestroyed.class))
                        .ifPresent(observerClassDeclaration::addAnnotation);

                methodDeclaration.getParameters().stream()
                        .filter(parameter -> parameter.isAnnotationPresent(ObservesAsync.class))
                        .findFirst()
                        .flatMap(parameter -> parameter.getAnnotationByClass(Destroyed.class))
                        .ifPresent(observerClassDeclaration::addAnnotation);

                methodDeclaration.getParameters().stream()
                        .filter(parameter -> parameter.isAnnotationPresent(ObservesAsync.class))
                        .findFirst()
                        .flatMap(parameter -> parameter.getAnnotationByClass(Priority.class))
                        .ifPresent(observerClassDeclaration::addAnnotation);

                if (processorManager.getQualifiedName(methodDeclaration.getType()).equals(Mono.class.getName())) {
                    proxyCompilationUnit.addImport(Void.class);

                    MethodDeclaration onEvent = new MethodDeclaration()
                            .setName("onEventAsync")
                            .setModifiers(Modifier.Keyword.PUBLIC)
                            .addParameter(new Parameter().setName(methodDeclaration.getParameter(0).getName()).setType(Object.class.getSimpleName()))
                            .setType(
                                    new ClassOrInterfaceType()
                                            .setName(Mono.class.getSimpleName())
                                            .setTypeArguments(new ClassOrInterfaceType().setName(Void.class.getSimpleName()))
                            )
                            .addAnnotation(Override.class);

                    onEvent.setBody(
                            new BlockStmt()
                                    .addStatement(
                                            new ReturnStmt(
                                                    new MethodCallExpr()
                                                            .setName(methodDeclaration.getName())
                                                            .addArgument(methodDeclaration.getParameter(0).getNameAsExpression())
                                            )
                                    )
                    );

                    observerClassDeclaration.addMember(onEvent);
                } else {
                    MethodDeclaration onEvent = new MethodDeclaration()
                            .setName("onEvent")
                            .setModifiers(Modifier.Keyword.PUBLIC)
                            .addParameter(new Parameter().setName(methodDeclaration.getParameter(0).getName()).setType(Object.class.getSimpleName()))
                            .setType(new VoidType())
                            .addAnnotation(Override.class);

                    onEvent.setBody(
                            new BlockStmt()
                                    .addStatement(
                                            new MethodCallExpr()
                                                    .setName(methodDeclaration.getName())
                                                    .addArgument(methodDeclaration.getParameter(0).getNameAsExpression())
                                    )
                    );

                    observerClassDeclaration.addMember(onEvent);
                }
                proxyClassDeclaration.addMember(observerClassDeclaration);
            });
        }

        componentProxyProcessors
                .forEach(componentProxyProcessor -> {
                    logger.info("{} process component proxy", componentProxyProcessor.getClass().getName());
                    if (componentProxyProcessor.match(typeElement)) {
                        componentProxyProcessor.processComponentProxy(componentCompilationUnit, componentClassDeclaration, proxyCompilationUnit, proxyClassDeclaration);
                    }
                });

        logger.info("{} proxy class build success", qualifiedName);
        return proxyCompilationUnit;
    }

    private CompilationUnit buildProxySuppliers(String rootPackageName, List<CompilationUnit> componentCompilationUnitList) {
        logger.info("{} proxy suppliers class build start", rootPackageName);

        ClassOrInterfaceDeclaration suppliersClassDeclaration = new ClassOrInterfaceDeclaration()
                .addModifier(Modifier.Keyword.PUBLIC)
                .setName("ProxySuppliers")
                .addAnnotation(new NormalAnnotationExpr().addPair("value", new StringLiteralExpr(getClass().getName())).setName(Generated.class.getSimpleName()))
                .addImplementedType(BeanSuppliers.class);

        CompilationUnit suppliersCompilationUnit = new CompilationUnit()
                .setPackageDeclaration(rootPackageName)
                .addType(suppliersClassDeclaration)
                .addImport(Generated.class)
                .addImport(BeanSuppliers.class)
                .addImport(BeanContext.class)
                .addImport(BeanSupplier.class)
                .addImport(Map.class)
                .addImport(HashMap.class);

        ClassOrInterfaceType beanSuppliersType = new ClassOrInterfaceType()
                .setName(Map.class.getSimpleName())
                .setTypeArguments(
                        new NodeList<>(
                                new ClassOrInterfaceType().setName(String.class.getSimpleName()),
                                new ClassOrInterfaceType().setName(Map.class.getSimpleName())
                                        .setTypeArguments(
                                                new NodeList<>(
                                                        new ClassOrInterfaceType().setName(String.class.getSimpleName()),
                                                        new ClassOrInterfaceType().setName(BeanSupplier.class.getSimpleName())
                                                )
                                        )
                        )
                );

        suppliersClassDeclaration.addMember(
                new FieldDeclaration()
                        .addModifier(Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC, Modifier.Keyword.FINAL)
                        .addVariable(
                                new VariableDeclarator()
                                        .setName("beanSuppliers")
                                        .setType(beanSuppliersType)
                                        .setInitializer(
                                                new ObjectCreationExpr()
                                                        .setType(new ClassOrInterfaceType().setName(HashMap.class.getSimpleName()).setTypeArguments())
                                        )
                        )
        );

        BlockStmt staticInitializer = new BlockStmt();

        suppliersClassDeclaration.addMember(new InitializerDeclaration(true, staticInitializer));

        componentCompilationUnitList.forEach(componentCompilationUnit ->
                processorManager.getPublicClassOrInterfaceDeclaration(componentCompilationUnit)
                        .ifPresent(componentClassDeclaration -> {
                            String qualifiedName = processorManager.getQualifiedName(componentClassDeclaration);
                            String componentPrefix = qualifiedName.replaceAll("\\.", "_");

                            staticInitializer
                                    .addStatement(
                                            new VariableDeclarationExpr()
                                                    .addVariable(
                                                            new VariableDeclarator().setName(componentPrefix + "_beanSupplier")
                                                                    .setType(BeanSupplier.class)
                                                                    .setInitializer(
                                                                            new ObjectCreationExpr()
                                                                                    .setType(BeanSupplier.class)
                                                                    )
                                                    )
                                    );

                            Expression objectCreateExpression = Optional.ofNullable(proxyCompilationUnitMap.get(qualifiedName))
                                    .flatMap(compilationUnit -> processorManager.getPublicClassOrInterfaceDeclaration(compilationUnit))
                                    .map(proxyClassDeclaration ->
                                            proxyClassDeclaration.getMethods().stream()
                                                    .filter(NodeWithStaticModifier::isStatic)
                                                    .filter(methodDeclaration -> methodDeclaration.isAnnotationPresent(Produces.class))
                                                    .filter(producesMethodDeclaration -> processorManager.getQualifiedName(producesMethodDeclaration.getType()).equals(qualifiedName + "_Proxy"))
                                                    .findFirst()
                                                    .map(producesMethodDeclaration ->
                                                            (Expression) new MethodCallExpr()
                                                                    .setName(producesMethodDeclaration.getName())
                                                                    .setArguments(
                                                                            producesMethodDeclaration.getParameters().stream()
                                                                                    .map(parameter -> getBeanGetMethodCallExpr(suppliersCompilationUnit, parameter, parameter.getType().asClassOrInterfaceType()))
                                                                                    .map(methodCallExpr -> (Expression) methodCallExpr)
                                                                                    .collect(Collectors.toCollection(NodeList::new))
                                                                    )
                                                                    .setScope(new NameExpr(qualifiedName + "_Proxy"))
                                                    )
                                                    .or(() ->
                                                            componentClassDeclaration.getMethods().stream()
                                                                    .filter(NodeWithStaticModifier::isStatic)
                                                                    .filter(methodDeclaration -> methodDeclaration.isAnnotationPresent(Produces.class))
                                                                    .filter(producesMethodDeclaration -> processorManager.getQualifiedName(producesMethodDeclaration.getType()).equals(qualifiedName))
                                                                    .findFirst()
                                                                    .map(producesMethodDeclaration ->
                                                                            (Expression) new MethodCallExpr()
                                                                                    .setName(producesMethodDeclaration.getName())
                                                                                    .setArguments(
                                                                                            producesMethodDeclaration.getParameters().stream()
                                                                                                    .map(parameter -> getBeanGetMethodCallExpr(suppliersCompilationUnit, parameter, parameter.getType().asClassOrInterfaceType()))
                                                                                                    .map(methodCallExpr -> (Expression) methodCallExpr)
                                                                                                    .collect(Collectors.toCollection(NodeList::new))
                                                                                    )
                                                                                    .setScope(new NameExpr(qualifiedName))
                                                                    )
                                                    )
                                                    .orElseGet(() ->
                                                            new ObjectCreationExpr()
                                                                    .setType(qualifiedName + "_Proxy")
                                                                    .setArguments(
                                                                            proxyClassDeclaration.getConstructors().stream()
                                                                                    .findFirst()
                                                                                    .map(constructorDeclaration ->
                                                                                            constructorDeclaration.getParameters().stream()
                                                                                                    .map(parameter -> getBeanGetMethodCallExpr(suppliersCompilationUnit, parameter, parameter.getType().asClassOrInterfaceType()))
                                                                                                    .map(methodCallExpr -> (Expression) methodCallExpr)
                                                                                                    .collect(Collectors.toCollection(NodeList::new))
                                                                                    )
                                                                                    .orElseGet(NodeList::new)
                                                                    )
                                                    )
                                    )
                                    .orElseGet(() ->
                                            componentClassDeclaration.getMethods().stream()
                                                    .filter(NodeWithStaticModifier::isStatic)
                                                    .filter(methodDeclaration -> methodDeclaration.isAnnotationPresent(Produces.class))
                                                    .filter(producesMethodDeclaration -> processorManager.getQualifiedName(producesMethodDeclaration.getType()).equals(qualifiedName))
                                                    .findFirst()
                                                    .map(producesMethodDeclaration ->
                                                            (Expression) new MethodCallExpr()
                                                                    .setName(producesMethodDeclaration.getName())
                                                                    .setArguments(
                                                                            producesMethodDeclaration.getParameters().stream()
                                                                                    .map(parameter -> getBeanGetMethodCallExpr(suppliersCompilationUnit, parameter, parameter.getType().asClassOrInterfaceType()))
                                                                                    .map(methodCallExpr -> (Expression) methodCallExpr)
                                                                                    .collect(Collectors.toCollection(NodeList::new))
                                                                    )
                                                                    .setScope(new NameExpr(qualifiedName))
                                                    )
                                                    .orElseGet(() ->
                                                            new ObjectCreationExpr()
                                                                    .setType(qualifiedName)
                                                                    .setArguments(
                                                                            componentClassDeclaration.getConstructors().stream()
                                                                                    .findFirst()
                                                                                    .map(constructorDeclaration ->
                                                                                            constructorDeclaration.getParameters().stream()
                                                                                                    .map(parameter -> getBeanGetMethodCallExpr(suppliersCompilationUnit, parameter, parameter.getType().asClassOrInterfaceType()))
                                                                                                    .map(methodCallExpr -> (Expression) methodCallExpr)
                                                                                                    .collect(Collectors.toCollection(NodeList::new))
                                                                                    )
                                                                                    .orElseGet(NodeList::new)
                                                                    )
                                                    )

                                    );

                            Expression getInstanceExpression;
                            if (componentClassDeclaration.isAnnotationPresent(Singleton.class) || componentClassDeclaration.isAnnotationPresent(ApplicationScoped.class)) {
                                ClassOrInterfaceDeclaration holderClassDeclaration = new ClassOrInterfaceDeclaration()
                                        .setName(componentPrefix + "Holder")
                                        .setModifiers(Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC);

                                holderClassDeclaration.addFieldWithInitializer(
                                                qualifiedName,
                                                "INSTANCE",
                                                objectCreateExpression
                                        )
                                        .setModifiers(Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC, Modifier.Keyword.FINAL);

                                suppliersClassDeclaration.addMember(holderClassDeclaration);

                                getInstanceExpression = new FieldAccessExpr()
                                        .setName("INSTANCE")
                                        .setScope(new NameExpr(componentPrefix + "Holder"));

                            } else if (componentClassDeclaration.isAnnotationPresent(RequestScoped.class) || componentClassDeclaration.isAnnotationPresent(SessionScoped.class) || componentClassDeclaration.isAnnotationPresent(TransactionScoped.class)) {
                                String scopedAnnotationName = processorManager.getScopedAnnotationName(componentClassDeclaration)
                                        .orElseThrow(() -> new InjectionProcessException(ANNOTATION_NOT_EXIST.bind(qualifiedName)));

                                suppliersCompilationUnit.addImport(ReactorBeanScoped.class)
                                        .addImport(Mono.class);

                                getInstanceExpression = new MethodCallExpr()
                                        .setName("get")
                                        .addArgument(new ClassExpr().setType(qualifiedName))
                                        .addArgument(
                                                new LambdaExpr()
                                                        .setEnclosingParameters(true)
                                                        .setBody(new ExpressionStmt(objectCreateExpression))
                                        )
                                        .setScope(
                                                new MethodCallExpr()
                                                        .setName("get")
                                                        .addArgument(new ClassExpr().setType(ReactorBeanScoped.class))
                                                        .addArgument(
                                                                new MethodCallExpr()
                                                                        .setName("of")
                                                                        .addArgument(new StringLiteralExpr(Named.class.getName()))
                                                                        .addArgument(new MethodCallExpr()
                                                                                .setName("of")
                                                                                .addArgument(new StringLiteralExpr("value"))
                                                                                .addArgument(new StringLiteralExpr(scopedAnnotationName))
                                                                                .setScope(new NameExpr("Map"))
                                                                        )
                                                                        .setScope(new NameExpr("Map"))
                                                        )
                                                        .setScope(new NameExpr(BeanContext.class.getSimpleName()))
                                        );
                            } else {
                                getInstanceExpression = objectCreateExpression;
                            }

                            componentClassDeclaration.getMethods().stream()
                                    .filter(methodDeclaration -> methodDeclaration.isAnnotationPresent(Produces.class))
                                    .filter(producesMethodDeclaration -> !processorManager.getQualifiedName(producesMethodDeclaration.getType()).equals(qualifiedName))
                                    .filter(producesMethodDeclaration -> producesMethodDeclaration.isAnnotationPresent(Singleton.class) || producesMethodDeclaration.isAnnotationPresent(ApplicationScoped.class))
                                    .forEach(producesMethodDeclaration -> {
                                        String methodTypeQualifiedName = processorManager.getQualifiedName(producesMethodDeclaration);
                                        String prefix = methodTypeQualifiedName.replaceAll("\\.", "_");
                                        ClassOrInterfaceDeclaration holderClassOrInterfaceDeclaration = new ClassOrInterfaceDeclaration();
                                        holderClassOrInterfaceDeclaration
                                                .setName(prefix + "Holder" + componentClassDeclaration.getMethods().indexOf(producesMethodDeclaration))
                                                .setModifiers(Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC)
                                                .addFieldWithInitializer(
                                                        methodTypeQualifiedName,
                                                        "INSTANCE",
                                                        new MethodCallExpr()
                                                                .setName(producesMethodDeclaration.getName())
                                                                .setArguments(
                                                                        producesMethodDeclaration.getParameters().stream()
                                                                                .map(parameter -> getBeanGetMethodCallExpr(suppliersCompilationUnit, parameter, parameter.getType().asClassOrInterfaceType()))
                                                                                .map(methodCallExpr -> (Expression) methodCallExpr)
                                                                                .collect(Collectors.toCollection(NodeList::new))
                                                                )
                                                                .setScope(
                                                                        new MethodCallExpr()
                                                                                .setName("get")
                                                                                .addArgument(new ClassExpr().setType(processorManager.getQualifiedName(componentClassDeclaration)))
                                                                                .setScope(new NameExpr().setName("BeanContext"))
                                                                )
                                                )
                                                .setModifiers(Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC, Modifier.Keyword.FINAL);

                                        suppliersClassDeclaration.addMember(holderClassOrInterfaceDeclaration);
                                    });

                            MethodCallExpr mapOf = new MethodCallExpr()
                                    .setName("of")
                                    .setScope(new NameExpr("Map"));

                            componentClassDeclaration.getAnnotations()
                                    .forEach(annotationExpr -> {
                                        if (processorManager.hasMetaAnnotation(annotationExpr, Qualifier.class.getName())) {
                                            mapOf.addArgument(new StringLiteralExpr(processorManager.getQualifiedName(annotationExpr)));
                                            mapOf.addArgument(qualifierToExpression(annotationExpr, suppliersCompilationUnit));
                                        }
                                    });

                            staticInitializer.addStatement(
                                    new MethodCallExpr().setName("setQualifiers")
                                            .addArgument(mapOf)
                                            .setScope(new NameExpr(componentPrefix + "_beanSupplier"))
                            );

                            staticInitializer.addStatement(
                                    new MethodCallExpr().setName("setSupplier")
                                            .addArgument(
                                                    new LambdaExpr()
                                                            .setEnclosingParameters(true)
                                                            .setBody(new ExpressionStmt(getInstanceExpression))
                                            )
                                            .setScope(new NameExpr(componentPrefix + "_beanSupplier"))
                            );

                            componentClassDeclaration.getAnnotationByClass(Priority.class)
                                    .ifPresent(annotation -> {
                                        if (annotation.isNormalAnnotationExpr()) {
                                            if (annotation.asNormalAnnotationExpr().getPairs().get(0).getValue().isNameExpr()) {
                                                processorManager.getMemberValueQualifiedName(annotation.asNormalAnnotationExpr().getPairs().get(0).getValue())
                                                        .forEach(numberValueQualifiedName -> suppliersCompilationUnit.addImport(numberValueQualifiedName + "." + annotation.asNormalAnnotationExpr().getPairs().get(0).getValue().asNameExpr().getNameAsString(), true, false));
                                            } else {
                                                processorManager.getMemberValueQualifiedName(annotation.asNormalAnnotationExpr().getPairs().get(0).getValue())
                                                        .forEach(suppliersCompilationUnit::addImport);
                                            }
                                            staticInitializer.addStatement(
                                                    new MethodCallExpr().setName("setPriority")
                                                            .addArgument(annotation.asNormalAnnotationExpr().getPairs().get(0).getValue().clone())
                                                            .setScope(new NameExpr(componentPrefix + "_beanSupplier"))
                                            );
                                        } else if (annotation.isSingleMemberAnnotationExpr()) {
                                            if (annotation.asSingleMemberAnnotationExpr().getMemberValue().isNameExpr()) {
                                                processorManager.getMemberValueQualifiedName(annotation.asSingleMemberAnnotationExpr().getMemberValue())
                                                        .forEach(numberValueQualifiedName -> suppliersCompilationUnit.addImport(numberValueQualifiedName + "." + annotation.asSingleMemberAnnotationExpr().getMemberValue().asNameExpr().getNameAsString(), true, false));
                                            } else {
                                                processorManager.getMemberValueQualifiedName(annotation.asSingleMemberAnnotationExpr().getMemberValue())
                                                        .forEach(suppliersCompilationUnit::addImport);
                                            }
                                            staticInitializer.addStatement(
                                                    new MethodCallExpr().setName("setPriority")
                                                            .addArgument(annotation.asSingleMemberAnnotationExpr().getMemberValue().clone())
                                                            .setScope(new NameExpr(componentPrefix + "_beanSupplier"))
                                            );
                                        }
                                    });

                            staticInitializer.addStatement(
                                    new MethodCallExpr().setName("put")
                                            .addArgument(new StringLiteralExpr(qualifiedName + "_Proxy"))
                                            .addArgument(new NameExpr(componentPrefix + "_beanSupplier"))
                                            .setScope(
                                                    new MethodCallExpr().setName("computeIfAbsent")
                                                            .addArgument(new StringLiteralExpr(qualifiedName))
                                                            .addArgument(
                                                                    new LambdaExpr()
                                                                            .addParameter(new Parameter().setName("k").setType(new UnknownType()))
                                                                            .setBody(new ExpressionStmt(new ObjectCreationExpr().setType(HashMap.class).setTypeArguments()))
                                                            )
                                                            .setScope(new NameExpr("beanSuppliers"))
                                            )
                            );

                            staticInitializer.addStatement(
                                    new MethodCallExpr().setName("put")
                                            .addArgument(new StringLiteralExpr(qualifiedName + "_Proxy"))
                                            .addArgument(new NameExpr(componentPrefix + "_beanSupplier"))
                                            .setScope(
                                                    new MethodCallExpr().setName("computeIfAbsent")
                                                            .addArgument(new StringLiteralExpr(qualifiedName + "_Proxy"))
                                                            .addArgument(
                                                                    new LambdaExpr()
                                                                            .addParameter(new Parameter().setName("k").setType(new UnknownType()))
                                                                            .setBody(new ExpressionStmt(new ObjectCreationExpr().setType(HashMap.class).setTypeArguments()))
                                                            )
                                                            .setScope(new NameExpr("beanSuppliers"))
                                            )
                            );

                            processorManager.getExtendedTypes(componentClassDeclaration)
                                    .forEach(extendedTypeName ->
                                            staticInitializer.addStatement(
                                                    new MethodCallExpr().setName("put")
                                                            .addArgument(new StringLiteralExpr(qualifiedName))
                                                            .addArgument(new NameExpr(componentPrefix + "_beanSupplier"))
                                                            .setScope(
                                                                    new MethodCallExpr().setName("computeIfAbsent")
                                                                            .addArgument(new StringLiteralExpr(extendedTypeName))
                                                                            .addArgument(
                                                                                    new LambdaExpr()
                                                                                            .addParameter(new Parameter().setName("k").setType(new UnknownType()))
                                                                                            .setBody(new ExpressionStmt(new ObjectCreationExpr().setType(HashMap.class).setTypeArguments()))
                                                                            )
                                                                            .setScope(new NameExpr("beanSuppliers"))
                                                            )
                                            )
                                    );

                            processorManager.getImplementedTypes(componentClassDeclaration)
                                    .forEach(implementedTypeName ->
                                            staticInitializer.addStatement(
                                                    new MethodCallExpr().setName("put")
                                                            .addArgument(new StringLiteralExpr(qualifiedName))
                                                            .addArgument(new NameExpr(componentPrefix + "_beanSupplier"))
                                                            .setScope(
                                                                    new MethodCallExpr().setName("computeIfAbsent")
                                                                            .addArgument(new StringLiteralExpr(implementedTypeName))
                                                                            .addArgument(
                                                                                    new LambdaExpr()
                                                                                            .addParameter(new Parameter().setName("k").setType(new UnknownType()))
                                                                                            .setBody(new ExpressionStmt(new ObjectCreationExpr().setType(HashMap.class).setTypeArguments()))
                                                                            )
                                                                            .setScope(new NameExpr("beanSuppliers"))
                                                            )
                                            )
                                    );

                            componentClassDeclaration.getMethods().stream()
                                    .filter(methodDeclaration -> methodDeclaration.isAnnotationPresent(Produces.class))
                                    .filter(producesMethodDeclaration -> !processorManager.getQualifiedName(producesMethodDeclaration.getType()).equals(qualifiedName))
                                    .forEach(producesMethodDeclaration -> {
                                        String methodTypeQualifiedName = processorManager.getQualifiedName(producesMethodDeclaration);
                                        String producesPrefix = methodTypeQualifiedName.replaceAll("\\.", "_");

                                        MethodCallExpr mapOfProduces = new MethodCallExpr()
                                                .setName("of")
                                                .setScope(new NameExpr("Map"));

                                        producesMethodDeclaration.getAnnotations()
                                                .forEach(annotationExpr -> {
                                                    if (processorManager.hasMetaAnnotation(annotationExpr, Qualifier.class.getName())) {
                                                        mapOfProduces.addArgument(new StringLiteralExpr(processorManager.getQualifiedName(annotationExpr)));
                                                        mapOfProduces.addArgument(qualifierToExpression(annotationExpr, suppliersCompilationUnit));
                                                    }
                                                });

                                        Expression producesCreateExpression;
                                        if (producesMethodDeclaration.isAnnotationPresent(Singleton.class) || producesMethodDeclaration.isAnnotationPresent(ApplicationScoped.class)) {
                                            producesCreateExpression = new FieldAccessExpr()
                                                    .setName("INSTANCE")
                                                    .setScope(new NameExpr(producesPrefix + "Holder" + componentClassDeclaration.getMethods().indexOf(producesMethodDeclaration)));
                                        } else if (producesMethodDeclaration.isAnnotationPresent(RequestScoped.class) || producesMethodDeclaration.isAnnotationPresent(SessionScoped.class) || producesMethodDeclaration.isAnnotationPresent(TransactionScoped.class)) {
                                            String scopedAnnotationName = processorManager.getScopedAnnotationName(producesMethodDeclaration)
                                                    .orElseThrow(() -> new InjectionProcessException(ANNOTATION_NOT_EXIST.bind(qualifiedName)));

                                            suppliersCompilationUnit.addImport(ReactorBeanScoped.class)
                                                    .addImport(Mono.class);

                                            producesCreateExpression = new MethodCallExpr()
                                                    .setName(processorManager.getQualifiedName(producesMethodDeclaration.getType()).equals(Mono.class.getName()) ? "getMono" : "get")
                                                    .addArgument(new ClassExpr().setType(methodTypeQualifiedName))
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .setEnclosingParameters(true)
                                                                    .setBody(
                                                                            new ExpressionStmt(
                                                                                    new MethodCallExpr()
                                                                                            .setName(producesMethodDeclaration.getName())
                                                                                            .setArguments(
                                                                                                    producesMethodDeclaration.getParameters().stream()
                                                                                                            .map(parameter -> getBeanGetMethodCallExpr(suppliersCompilationUnit, parameter, parameter.getType().asClassOrInterfaceType()))
                                                                                                            .map(methodCallExpr -> (Expression) methodCallExpr)
                                                                                                            .collect(Collectors.toCollection(NodeList::new))
                                                                                            )
                                                                                            .setScope(
                                                                                                    new MethodCallExpr()
                                                                                                            .setName("get")
                                                                                                            .addArgument(new ClassExpr().setType(processorManager.getQualifiedName(componentClassDeclaration)))
                                                                                                            .setScope(new NameExpr().setName("BeanContext"))
                                                                                            )

                                                                            )
                                                                    )
                                                    )
                                                    .setScope(
                                                            new MethodCallExpr()
                                                                    .setName("get")
                                                                    .addArgument(new ClassExpr().setType(ReactorBeanScoped.class))
                                                                    .addArgument(
                                                                            new MethodCallExpr()
                                                                                    .setName("of")
                                                                                    .addArgument(new StringLiteralExpr(Named.class.getName()))
                                                                                    .addArgument(new MethodCallExpr()
                                                                                            .setName("of")
                                                                                            .addArgument(new StringLiteralExpr("value"))
                                                                                            .addArgument(new StringLiteralExpr(scopedAnnotationName))
                                                                                            .setScope(new NameExpr("Map"))
                                                                                    )
                                                                                    .setScope(new NameExpr("Map"))
                                                                    )
                                                                    .setScope(new NameExpr(BeanContext.class.getSimpleName()))
                                                    );
                                        } else {
                                            producesCreateExpression = new MethodCallExpr()
                                                    .setName(producesMethodDeclaration.getName())
                                                    .setArguments(
                                                            producesMethodDeclaration.getParameters().stream()
                                                                    .map(parameter -> getBeanGetMethodCallExpr(suppliersCompilationUnit, parameter, parameter.getType().asClassOrInterfaceType()))
                                                                    .map(methodCallExpr -> (Expression) methodCallExpr)
                                                                    .collect(Collectors.toCollection(NodeList::new))
                                                    )
                                                    .setScope(
                                                            new MethodCallExpr()
                                                                    .setName("get")
                                                                    .addArgument(new ClassExpr().setType(processorManager.getQualifiedName(componentClassDeclaration)))
                                                                    .setScope(new NameExpr().setName("BeanContext"))
                                                    );
                                        }

                                        staticInitializer.addStatement(
                                                new VariableDeclarationExpr()
                                                        .addVariable(
                                                                new VariableDeclarator().setName(producesPrefix + "_beanSupplier")
                                                                        .setType(BeanSupplier.class)
                                                                        .setInitializer(
                                                                                new ObjectCreationExpr()
                                                                                        .setType(BeanSupplier.class)
                                                                        )
                                                        )
                                        );

                                        staticInitializer.addStatement(
                                                new MethodCallExpr().setName("setQualifiers")
                                                        .addArgument(mapOfProduces)
                                                        .setScope(new NameExpr(producesPrefix + "_beanSupplier"))
                                        );

                                        staticInitializer.addStatement(
                                                new MethodCallExpr().setName("setSupplier")
                                                        .addArgument(
                                                                new LambdaExpr()
                                                                        .setEnclosingParameters(true)
                                                                        .setBody(new ExpressionStmt(producesCreateExpression))
                                                        )
                                                        .setScope(new NameExpr(producesPrefix + "_beanSupplier"))
                                        );

                                        producesMethodDeclaration.getAnnotationByClass(Priority.class)
                                                .ifPresent(annotation -> {
                                                    if (annotation.isNormalAnnotationExpr()) {
                                                        if (annotation.asNormalAnnotationExpr().getPairs().get(0).getValue().isNameExpr()) {
                                                            processorManager.getMemberValueQualifiedName(annotation.asNormalAnnotationExpr().getPairs().get(0).getValue())
                                                                    .forEach(numberValueQualifiedName -> suppliersCompilationUnit.addImport(numberValueQualifiedName + "." + annotation.asNormalAnnotationExpr().getPairs().get(0).getValue().asNameExpr().getNameAsString(), true, false));
                                                        } else {
                                                            processorManager.getMemberValueQualifiedName(annotation.asNormalAnnotationExpr().getPairs().get(0).getValue())
                                                                    .forEach(suppliersCompilationUnit::addImport);
                                                        }
                                                        staticInitializer.addStatement(
                                                                new MethodCallExpr().setName("setPriority")
                                                                        .addArgument(annotation.asNormalAnnotationExpr().getPairs().get(0).getValue().clone())
                                                                        .setScope(new NameExpr(producesPrefix + "_beanSupplier"))
                                                        );
                                                    } else if (annotation.isSingleMemberAnnotationExpr()) {
                                                        if (annotation.asSingleMemberAnnotationExpr().getMemberValue().isNameExpr()) {
                                                            processorManager.getMemberValueQualifiedName(annotation.asSingleMemberAnnotationExpr().getMemberValue())
                                                                    .forEach(numberValueQualifiedName -> suppliersCompilationUnit.addImport(numberValueQualifiedName + "." + annotation.asSingleMemberAnnotationExpr().getMemberValue().asNameExpr().getNameAsString(), true, false));
                                                        } else {
                                                            processorManager.getMemberValueQualifiedName(annotation.asSingleMemberAnnotationExpr().getMemberValue())
                                                                    .forEach(suppliersCompilationUnit::addImport);
                                                        }
                                                        staticInitializer.addStatement(
                                                                new MethodCallExpr().setName("setPriority")
                                                                        .addArgument(annotation.asSingleMemberAnnotationExpr().getMemberValue().clone())
                                                                        .setScope(new NameExpr(producesPrefix + "_beanSupplier"))
                                                        );
                                                    }
                                                });

                                        staticInitializer.addStatement(
                                                new MethodCallExpr().setName("put")
                                                        .addArgument(new StringLiteralExpr(methodTypeQualifiedName))
                                                        .addArgument(new NameExpr(producesPrefix + "_beanSupplier"))
                                                        .setScope(
                                                                new MethodCallExpr().setName("computeIfAbsent")
                                                                        .addArgument(new StringLiteralExpr(methodTypeQualifiedName))
                                                                        .addArgument(
                                                                                new LambdaExpr()
                                                                                        .addParameter(new Parameter().setName("k").setType(new UnknownType()))
                                                                                        .setBody(new ExpressionStmt(new ObjectCreationExpr().setType(HashMap.class).setTypeArguments()))
                                                                        )
                                                                        .setScope(new NameExpr("beanSuppliers"))
                                                        )
                                        );

                                        processorManager.getClassOrInterfaceDeclaration(methodTypeQualifiedName)
                                                .ifPresent(returnTypeClassOrInterfaceDeclaration -> {
                                                    processorManager.getExtendedTypes(returnTypeClassOrInterfaceDeclaration)
                                                            .forEach(extendedTypeName ->
                                                                    staticInitializer.addStatement(
                                                                            new MethodCallExpr().setName("put")
                                                                                    .addArgument(new StringLiteralExpr(methodTypeQualifiedName))
                                                                                    .addArgument(new NameExpr(producesPrefix + "_beanSupplier"))
                                                                                    .setScope(
                                                                                            new MethodCallExpr().setName("computeIfAbsent")
                                                                                                    .addArgument(new StringLiteralExpr(extendedTypeName))
                                                                                                    .addArgument(
                                                                                                            new LambdaExpr()
                                                                                                                    .addParameter(new Parameter().setName("k").setType(new UnknownType()))
                                                                                                                    .setBody(new ExpressionStmt(new ObjectCreationExpr().setType(HashMap.class).setTypeArguments()))
                                                                                                    )
                                                                                                    .setScope(new NameExpr("beanSuppliers"))
                                                                                    )
                                                                    )
                                                            );

                                                    processorManager.getImplementedTypes(returnTypeClassOrInterfaceDeclaration)
                                                            .forEach(implementedTypeName ->
                                                                    staticInitializer.addStatement(
                                                                            new MethodCallExpr().setName("put")
                                                                                    .addArgument(new StringLiteralExpr(methodTypeQualifiedName))
                                                                                    .addArgument(new NameExpr(producesPrefix + "_beanSupplier"))
                                                                                    .setScope(
                                                                                            new MethodCallExpr().setName("computeIfAbsent")
                                                                                                    .addArgument(new StringLiteralExpr(implementedTypeName))
                                                                                                    .addArgument(
                                                                                                            new LambdaExpr()
                                                                                                                    .addParameter(new Parameter().setName("k").setType(new UnknownType()))
                                                                                                                    .setBody(new ExpressionStmt(new ObjectCreationExpr().setType(HashMap.class).setTypeArguments()))
                                                                                                    )
                                                                                                    .setScope(new NameExpr("beanSuppliers"))
                                                                                    )
                                                                    )
                                                            );
                                                });
                                    });

                            List<MethodDeclaration> observesMethods = componentClassDeclaration.getMethods().stream()
                                    .filter(methodDeclaration ->
                                            methodDeclaration.getParameters().stream()
                                                    .anyMatch(parameter -> parameter.isAnnotationPresent(Observes.class))
                                    )
                                    .collect(Collectors.toList());

                            if (!observesMethods.isEmpty()) {
                                observesMethods.forEach(methodDeclaration -> {
                                    String prefix = componentClassDeclaration.getNameAsString() + "_" + methodDeclaration.getNameAsString() + "_Observer";
                                    suppliersCompilationUnit.addImport(ScopeEventObserver.class);
                                    ClassOrInterfaceDeclaration holderClassOrInterfaceDeclaration = new ClassOrInterfaceDeclaration();

                                    holderClassOrInterfaceDeclaration
                                            .setName(prefix + "Holder")
                                            .setModifiers(Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC)
                                            .addFieldWithInitializer(
                                                    ScopeEventObserver.class.getSimpleName(),
                                                    "INSTANCE",
                                                    new ObjectCreationExpr()
                                                            .setType(
                                                                    new ClassOrInterfaceType()
                                                                            .setName(prefix)
                                                            )
                                                            .setScope(
                                                                    new MethodCallExpr()
                                                                            .setName("get")
                                                                            .addArgument(new ClassExpr().setType(qualifiedName + "_Proxy"))
                                                                            .setScope(new NameExpr().setName("BeanContext"))
                                                            )
                                            )
                                            .setModifiers(Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC, Modifier.Keyword.FINAL);

                                    suppliersClassDeclaration.addMember(holderClassOrInterfaceDeclaration);

                                    MethodCallExpr mapOfProduces = new MethodCallExpr()
                                            .setName("of")
                                            .setScope(new NameExpr("Map"));

                                    methodDeclaration.getParameters().stream()
                                            .filter(parameter -> parameter.isAnnotationPresent(Observes.class))
                                            .flatMap(parameter -> parameter.getAnnotations().stream())
                                            .forEach(annotationExpr -> {
                                                if (processorManager.hasMetaAnnotation(annotationExpr, Qualifier.class.getName())) {
                                                    mapOfProduces.addArgument(new StringLiteralExpr(processorManager.getQualifiedName(annotationExpr)));
                                                    mapOfProduces.addArgument(qualifierToExpression(annotationExpr, suppliersCompilationUnit));
                                                }
                                            });

                                    staticInitializer.addStatement(
                                            new VariableDeclarationExpr()
                                                    .addVariable(
                                                            new VariableDeclarator().setName(prefix + "_beanSupplier")
                                                                    .setType(BeanSupplier.class)
                                                                    .setInitializer(
                                                                            new ObjectCreationExpr()
                                                                                    .setType(BeanSupplier.class)
                                                                    )
                                                    )
                                    );

                                    staticInitializer.addStatement(
                                            new MethodCallExpr().setName("setQualifiers")
                                                    .addArgument(mapOfProduces)
                                                    .setScope(new NameExpr(prefix + "_beanSupplier"))
                                    );

                                    staticInitializer.addStatement(
                                            new MethodCallExpr().setName("setSupplier")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .setEnclosingParameters(true)
                                                                    .setBody(
                                                                            new ExpressionStmt(
                                                                                    new FieldAccessExpr()
                                                                                            .setName("INSTANCE")
                                                                                            .setScope(new NameExpr(prefix + "Holder"))
                                                                            )
                                                                    )
                                                    )
                                                    .setScope(new NameExpr(prefix + "_beanSupplier"))
                                    );

                                    methodDeclaration.getParameters().stream()
                                            .filter(parameter -> parameter.isAnnotationPresent(Observes.class))
                                            .findFirst()
                                            .flatMap(parameter -> parameter.getAnnotationByClass(Priority.class))
                                            .ifPresent(annotation -> {
                                                if (annotation.isNormalAnnotationExpr()) {
                                                    if (annotation.asNormalAnnotationExpr().getPairs().get(0).getValue().isNameExpr()) {
                                                        processorManager.getMemberValueQualifiedName(annotation.asNormalAnnotationExpr().getPairs().get(0).getValue())
                                                                .forEach(numberValueQualifiedName -> suppliersCompilationUnit.addImport(numberValueQualifiedName + "." + annotation.asNormalAnnotationExpr().getPairs().get(0).getValue().asNameExpr().getNameAsString(), true, false));
                                                    } else {
                                                        processorManager.getMemberValueQualifiedName(annotation.asNormalAnnotationExpr().getPairs().get(0).getValue())
                                                                .forEach(suppliersCompilationUnit::addImport);
                                                    }
                                                    staticInitializer.addStatement(
                                                            new MethodCallExpr().setName("setPriority")
                                                                    .addArgument(annotation.asNormalAnnotationExpr().getPairs().get(0).getValue().clone())
                                                                    .setScope(new NameExpr(prefix + "_beanSupplier"))
                                                    );
                                                } else if (annotation.isSingleMemberAnnotationExpr()) {
                                                    if (annotation.asSingleMemberAnnotationExpr().getMemberValue().isNameExpr()) {
                                                        processorManager.getMemberValueQualifiedName(annotation.asSingleMemberAnnotationExpr().getMemberValue())
                                                                .forEach(numberValueQualifiedName -> suppliersCompilationUnit.addImport(numberValueQualifiedName + "." + annotation.asSingleMemberAnnotationExpr().getMemberValue().asNameExpr().getNameAsString(), true, false));
                                                    } else {
                                                        processorManager.getMemberValueQualifiedName(annotation.asSingleMemberAnnotationExpr().getMemberValue())
                                                                .forEach(suppliersCompilationUnit::addImport);
                                                    }
                                                    staticInitializer.addStatement(
                                                            new MethodCallExpr().setName("setPriority")
                                                                    .addArgument(annotation.asSingleMemberAnnotationExpr().getMemberValue().clone())
                                                                    .setScope(new NameExpr(prefix + "_beanSupplier"))
                                                    );
                                                }
                                            });

                                    staticInitializer.addStatement(
                                            new MethodCallExpr().setName("put")
                                                    .addArgument(new StringLiteralExpr(qualifiedName + "_Proxy." + componentClassDeclaration.getNameAsString() + "_" + methodDeclaration.getNameAsString() + "_Observer"))
                                                    .addArgument(new NameExpr(prefix + "_beanSupplier"))
                                                    .setScope(
                                                            new MethodCallExpr().setName("computeIfAbsent")
                                                                    .addArgument(new StringLiteralExpr(ScopeEventObserver.class.getName()))
                                                                    .addArgument(
                                                                            new LambdaExpr()
                                                                                    .addParameter(new Parameter().setName("k").setType(new UnknownType()))
                                                                                    .setBody(new ExpressionStmt(new ObjectCreationExpr().setType(HashMap.class).setTypeArguments()))
                                                                    )
                                                                    .setScope(new NameExpr("beanSuppliers"))
                                                    )
                                    );
                                });
                            }

                            List<MethodDeclaration> observesAsyncMethods = componentClassDeclaration.getMethods().stream()
                                    .filter(methodDeclaration ->
                                            methodDeclaration.getParameters().stream()
                                                    .anyMatch(parameter -> parameter.isAnnotationPresent(ObservesAsync.class))
                                    )
                                    .collect(Collectors.toList());

                            if (!observesAsyncMethods.isEmpty()) {
                                observesAsyncMethods.forEach(methodDeclaration -> {
                                    String prefix = componentClassDeclaration.getNameAsString() + "_" + methodDeclaration.getNameAsString() + "_Observer";
                                    suppliersCompilationUnit.addImport(ScopeEventAsyncObserver.class);
                                    ClassOrInterfaceDeclaration holderClassOrInterfaceDeclaration = new ClassOrInterfaceDeclaration();

                                    holderClassOrInterfaceDeclaration
                                            .setName(prefix + "Holder")
                                            .setModifiers(Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC)
                                            .addFieldWithInitializer(
                                                    ScopeEventAsyncObserver.class.getSimpleName(),
                                                    "INSTANCE",
                                                    new ObjectCreationExpr()
                                                            .setType(
                                                                    new ClassOrInterfaceType()
                                                                            .setName(prefix)
                                                            )
                                                            .setScope(
                                                                    new MethodCallExpr()
                                                                            .setName("get")
                                                                            .addArgument(new ClassExpr().setType(qualifiedName + "_Proxy"))
                                                                            .setScope(new NameExpr().setName("BeanContext"))
                                                            )
                                            )
                                            .setModifiers(Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC, Modifier.Keyword.FINAL);

                                    suppliersClassDeclaration.addMember(holderClassOrInterfaceDeclaration);

                                    MethodCallExpr mapOfProduces = new MethodCallExpr()
                                            .setName("of")
                                            .setScope(new NameExpr("Map"));

                                    methodDeclaration.getParameters().stream()
                                            .filter(parameter -> parameter.isAnnotationPresent(ObservesAsync.class))
                                            .flatMap(parameter -> parameter.getAnnotations().stream())
                                            .forEach(annotationExpr -> {
                                                if (processorManager.hasMetaAnnotation(annotationExpr, Qualifier.class.getName())) {
                                                    mapOfProduces.addArgument(new StringLiteralExpr(processorManager.getQualifiedName(annotationExpr)));
                                                    mapOfProduces.addArgument(qualifierToExpression(annotationExpr, suppliersCompilationUnit));
                                                }
                                            });

                                    staticInitializer.addStatement(
                                            new VariableDeclarationExpr()
                                                    .addVariable(
                                                            new VariableDeclarator().setName(prefix + "_beanSupplier")
                                                                    .setType(BeanSupplier.class)
                                                                    .setInitializer(
                                                                            new ObjectCreationExpr()
                                                                                    .setType(BeanSupplier.class)
                                                                    )
                                                    )
                                    );

                                    staticInitializer.addStatement(
                                            new MethodCallExpr().setName("setQualifiers")
                                                    .addArgument(mapOfProduces)
                                                    .setScope(new NameExpr(prefix + "_beanSupplier"))
                                    );

                                    staticInitializer.addStatement(
                                            new MethodCallExpr().setName("setSupplier")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .setEnclosingParameters(true)
                                                                    .setBody(
                                                                            new ExpressionStmt(
                                                                                    new FieldAccessExpr()
                                                                                            .setName("INSTANCE")
                                                                                            .setScope(new NameExpr(prefix + "Holder"))
                                                                            )
                                                                    )
                                                    )
                                                    .setScope(new NameExpr(prefix + "_beanSupplier"))
                                    );

                                    methodDeclaration.getParameters().stream()
                                            .filter(parameter -> parameter.isAnnotationPresent(ObservesAsync.class))
                                            .findFirst()
                                            .flatMap(parameter -> parameter.getAnnotationByClass(Priority.class))
                                            .ifPresent(annotation -> {
                                                if (annotation.isNormalAnnotationExpr()) {
                                                    if (annotation.asNormalAnnotationExpr().getPairs().get(0).getValue().isNameExpr()) {
                                                        processorManager.getMemberValueQualifiedName(annotation.asNormalAnnotationExpr().getPairs().get(0).getValue())
                                                                .forEach(numberValueQualifiedName -> suppliersCompilationUnit.addImport(numberValueQualifiedName + "." + annotation.asNormalAnnotationExpr().getPairs().get(0).getValue().asNameExpr().getNameAsString(), true, false));
                                                    } else {
                                                        processorManager.getMemberValueQualifiedName(annotation.asNormalAnnotationExpr().getPairs().get(0).getValue())
                                                                .forEach(suppliersCompilationUnit::addImport);
                                                    }
                                                    staticInitializer.addStatement(
                                                            new MethodCallExpr().setName("setPriority")
                                                                    .addArgument(annotation.asNormalAnnotationExpr().getPairs().get(0).getValue().clone())
                                                                    .setScope(new NameExpr(prefix + "_beanSupplier"))
                                                    );
                                                } else if (annotation.isSingleMemberAnnotationExpr()) {
                                                    if (annotation.asSingleMemberAnnotationExpr().getMemberValue().isNameExpr()) {
                                                        processorManager.getMemberValueQualifiedName(annotation.asSingleMemberAnnotationExpr().getMemberValue())
                                                                .forEach(numberValueQualifiedName -> suppliersCompilationUnit.addImport(numberValueQualifiedName + "." + annotation.asSingleMemberAnnotationExpr().getMemberValue().asNameExpr().getNameAsString(), true, false));
                                                    } else {
                                                        processorManager.getMemberValueQualifiedName(annotation.asSingleMemberAnnotationExpr().getMemberValue())
                                                                .forEach(suppliersCompilationUnit::addImport);
                                                    }
                                                    staticInitializer.addStatement(
                                                            new MethodCallExpr().setName("setPriority")
                                                                    .addArgument(annotation.asSingleMemberAnnotationExpr().getMemberValue().clone())
                                                                    .setScope(new NameExpr(prefix + "_beanSupplier"))
                                                    );
                                                }
                                            });

                                    staticInitializer.addStatement(
                                            new MethodCallExpr().setName("put")
                                                    .addArgument(new StringLiteralExpr(qualifiedName + "_Proxy." + componentClassDeclaration.getNameAsString() + "_" + methodDeclaration.getNameAsString() + "_Observer"))
                                                    .addArgument(new NameExpr(prefix + "_beanSupplier"))
                                                    .setScope(
                                                            new MethodCallExpr().setName("computeIfAbsent")
                                                                    .addArgument(new StringLiteralExpr(ScopeEventAsyncObserver.class.getName()))
                                                                    .addArgument(
                                                                            new LambdaExpr()
                                                                                    .addParameter(new Parameter().setName("k").setType(new UnknownType()))
                                                                                    .setBody(new ExpressionStmt(new ObjectCreationExpr().setType(HashMap.class).setTypeArguments()))
                                                                    )
                                                                    .setScope(new NameExpr("beanSuppliers"))
                                                    )
                                    );
                                });
                            }
                        })
        );


        MethodDeclaration getBeanSuppliers = new MethodDeclaration()
                .setName("getBeanSuppliers")
                .setModifiers(Modifier.Keyword.PUBLIC)
                .setType(beanSuppliersType)
                .addAnnotation(Override.class);

        getBeanSuppliers.createBody()
                .addStatement(new ReturnStmt().setExpression(new NameExpr("beanSuppliers")));

        suppliersClassDeclaration.addMember(getBeanSuppliers);

        logger.info("{} proxy suppliers class build success", rootPackageName);
        return suppliersCompilationUnit;
    }

    private Expression qualifierToExpression(AnnotationExpr qualifier) {
        return qualifierToExpression(qualifier, null);
    }

    private Expression qualifierToExpression(AnnotationExpr qualifier, CompilationUnit compilationUnit) {
        MethodCallExpr mapOf = new MethodCallExpr()
                .setName("of")
                .setScope(new NameExpr("Map"));
        if (qualifier.isNormalAnnotationExpr()) {
            qualifier.asNormalAnnotationExpr().getPairs()
                    .forEach(pair -> {
                        if (compilationUnit != null) {
                            if (pair.getValue().isNameExpr()) {
                                processorManager.getMemberValueQualifiedName(pair.getValue())
                                        .forEach(qualifiedName -> compilationUnit.addImport(qualifiedName + "." + pair.getValue().asNameExpr().getNameAsString(), true, false));
                            } else {
                                processorManager.getMemberValueQualifiedName(pair.getValue())
                                        .forEach(compilationUnit::addImport);
                            }
                        }
                        mapOf.addArgument(new StringLiteralExpr(pair.getNameAsString()));
                        mapOf.addArgument(pair.getValue().clone());
                    });
        } else if (qualifier.isSingleMemberAnnotationExpr()) {
            if (compilationUnit != null) {
                if (qualifier.asSingleMemberAnnotationExpr().getMemberValue().isNameExpr()) {
                    processorManager.getMemberValueQualifiedName(qualifier.asSingleMemberAnnotationExpr().getMemberValue())
                            .forEach(qualifiedName -> compilationUnit.addImport(qualifiedName + "." + qualifier.asSingleMemberAnnotationExpr().getMemberValue().asNameExpr().getNameAsString(), true, false));
                } else {
                    processorManager.getMemberValueQualifiedName(qualifier.asSingleMemberAnnotationExpr().getMemberValue())
                            .forEach(compilationUnit::addImport);
                }
            }
            mapOf.addArgument(new StringLiteralExpr("value"));
            mapOf.addArgument(qualifier.asSingleMemberAnnotationExpr().getMemberValue().clone());
        }
        return mapOf;
    }

    private MethodCallExpr getBeanGetMethodCallExpr(CompilationUnit proxyCompilationUnit, NodeWithAnnotations<?> annotations, ClassOrInterfaceType classOrInterfaceType) {
        MethodCallExpr mapOf = new MethodCallExpr()
                .setName("of")
                .setScope(new NameExpr("Map"));

        annotations.getAnnotations()
                .forEach(annotationExpr -> {
                    if (processorManager.hasMetaAnnotation(annotationExpr, Qualifier.class.getName())) {
                        mapOf.addArgument(new StringLiteralExpr(processorManager.getQualifiedName(annotationExpr)));
                        mapOf.addArgument(qualifierToExpression(annotationExpr));
                    }
                });

        MethodCallExpr methodCallExpr;
        String qualifiedName = processorManager.getQualifiedName(classOrInterfaceType);
        if (qualifiedName.equals(Provider.class.getName())) {
            Type type = classOrInterfaceType.getTypeArguments().orElseThrow(() -> new InjectionProcessException(PROVIDER_TYPE_NOT_EXIST)).get(0);
            if (type.isClassOrInterfaceType() && processorManager.getQualifiedName(type).equals(Mono.class.getName())) {
                methodCallExpr = new MethodCallExpr()
                        .setName("getMonoProvider")
                        .setScope(new NameExpr().setName("BeanContext"))
                        .addArgument(new ClassExpr().setType(processorManager.getQualifiedName(type.asClassOrInterfaceType().getTypeArguments().orElseThrow(() -> new InjectionProcessException(INSTANCE_TYPE_NOT_EXIST)).get(0))));

            } else {
                methodCallExpr = new MethodCallExpr()
                        .setName("getProvider")
                        .setScope(new NameExpr().setName("BeanContext"))
                        .addArgument(new ClassExpr().setType(processorManager.getQualifiedName(type)));
            }
            proxyCompilationUnit.addImport(Provider.class);
        } else if (qualifiedName.equals(Instance.class.getName())) {
            Type type = classOrInterfaceType.getTypeArguments().orElseThrow(() -> new InjectionProcessException(PROVIDER_TYPE_NOT_EXIST)).get(0);
            if (type.isClassOrInterfaceType() && processorManager.getQualifiedName(type).equals(Mono.class.getName())) {
                methodCallExpr = new MethodCallExpr()
                        .setName("getMonoInstance")
                        .setScope(new NameExpr().setName("BeanContext"))
                        .addArgument(new ClassExpr().setType(processorManager.getQualifiedName(type.asClassOrInterfaceType().getTypeArguments().orElseThrow(() -> new InjectionProcessException(INSTANCE_TYPE_NOT_EXIST)).get(0))));

            } else {
                methodCallExpr = new MethodCallExpr()
                        .setName("getInstance")
                        .setScope(new NameExpr().setName("BeanContext"))
                        .addArgument(new ClassExpr().setType(processorManager.getQualifiedName(type)));
            }
            proxyCompilationUnit.addImport(Instance.class);
        } else {
            if (qualifiedName.equals(Mono.class.getName())) {
                methodCallExpr = new MethodCallExpr()
                        .setName("getMono")
                        .setScope(new NameExpr().setName("BeanContext"))
                        .addArgument(new ClassExpr().setType(processorManager.getQualifiedName(classOrInterfaceType.getTypeArguments().orElseThrow(() -> new InjectionProcessException(INSTANCE_TYPE_NOT_EXIST)).get(0))));
            } else {
                methodCallExpr = new MethodCallExpr()
                        .setName("get")
                        .setScope(new NameExpr().setName("BeanContext"))
                        .addArgument(new ClassExpr().setType(qualifiedName));
            }
        }
        methodCallExpr.addArgument(mapOf);
        return methodCallExpr;
    }
}
