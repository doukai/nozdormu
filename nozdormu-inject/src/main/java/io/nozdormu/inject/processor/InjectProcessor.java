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
import com.google.auto.service.AutoService;
import com.google.common.collect.Streams;
import io.nozdormu.common.ProcessorManager;
import io.nozdormu.spi.context.*;
import io.nozdormu.spi.error.InjectionProcessException;
import jakarta.annotation.Generated;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.SessionScoped;
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
import javax.lang.model.element.TypeElement;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        componentProxyProcessors
                .forEach(componentProxyProcessor -> {
                    logger.info("{} in process", componentProxyProcessor.getClass().getName());
                    componentProxyProcessor.inProcess();
                });

        List<CompilationUnit> componentCompilationUnits = typeElements.stream()
                .flatMap(typeElement -> {
                    CompilationUnit proxyCompilationUnit = buildComponentProxy(typeElement);
                    CompilationUnit suppliersCompilationUnit = buildComponentSuppliers(typeElement, processorManager.getPublicClassOrInterfaceDeclarationOrError(proxyCompilationUnit));
                    return Stream.of(proxyCompilationUnit, suppliersCompilationUnit);
                })
                .collect(Collectors.toList());

        componentCompilationUnits.forEach(compilationUnit -> processorManager.writeToFiler(compilationUnit));

        return false;
    }

    private CompilationUnit buildComponentProxy(TypeElement typeElement) {
        return processorManager.getCompilationUnit(typeElement)
                .map(this::buildComponentProxy)
                .orElseThrow(() -> new InjectionProcessException(CANNOT_GET_COMPILATION_UNIT.bind(typeElement.getQualifiedName().toString())));
    }

    private CompilationUnit buildComponentProxy(CompilationUnit componentCompilationUnit) {
        return buildComponentProxy(
                componentCompilationUnit,
                processorManager.getPublicClassOrInterfaceDeclarationOrError(componentCompilationUnit)
        );
    }

    private CompilationUnit buildComponentProxy(CompilationUnit componentCompilationUnit, ClassOrInterfaceDeclaration componentClassDeclaration) {
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

        componentProxyProcessors
                .forEach(componentProxyProcessor -> {
                    logger.info("{} process component proxy", componentProxyProcessor.getClass().getName());
                    componentProxyProcessor.processComponentProxy(componentCompilationUnit, componentClassDeclaration, proxyCompilationUnit, proxyClassDeclaration);
                });

        logger.info("{} proxy class build success", qualifiedName);
        return proxyCompilationUnit;
    }

    private CompilationUnit buildComponentSuppliers(TypeElement typeElement, ClassOrInterfaceDeclaration proxyClassDeclaration) {
        return processorManager.getCompilationUnit(typeElement)
                .map(compilationUnit -> buildComponentSuppliers(compilationUnit, proxyClassDeclaration))
                .orElseThrow(() -> new InjectionProcessException(CANNOT_GET_COMPILATION_UNIT.bind(typeElement.getQualifiedName().toString())));
    }

    private CompilationUnit buildComponentSuppliers(CompilationUnit componentCompilationUnit, ClassOrInterfaceDeclaration proxyClassDeclaration) {
        return buildComponentSuppliers(
                componentCompilationUnit,
                processorManager.getPublicClassOrInterfaceDeclarationOrError(componentCompilationUnit),
                proxyClassDeclaration
        );
    }

    private CompilationUnit buildComponentSuppliers(CompilationUnit componentCompilationUnit, ClassOrInterfaceDeclaration componentClassDeclaration, ClassOrInterfaceDeclaration proxyClassDeclaration) {
        String qualifiedName = processorManager.getQualifiedName(componentClassDeclaration);

        logger.info("{} suppliers class build start", qualifiedName);

        ClassOrInterfaceDeclaration suppliersClassDeclaration = new ClassOrInterfaceDeclaration()
                .addModifier(Modifier.Keyword.PUBLIC)
                .setName(componentClassDeclaration.getNameAsString() + "_Suppliers")
                .addAnnotation(new NormalAnnotationExpr().addPair("value", new StringLiteralExpr(getClass().getName())).setName(Generated.class.getSimpleName()))
                .addAnnotation(new SingleMemberAnnotationExpr().setMemberValue(new ClassExpr().setType(BeanSuppliers.class)).setName(AutoService.class.getSimpleName()))
                .addImplementedType(BeanSuppliers.class);

        CompilationUnit suppliersCompilationUnit = new CompilationUnit()
                .addType(suppliersClassDeclaration)
                .addImport(Generated.class)
                .addImport(AutoService.class)
                .addImport(BeanSuppliers.class)
                .addImport(BeanContext.class)
                .addImport(BeanSupplier.class)
                .addImport(Map.class)
                .addImport(HashMap.class);

        componentCompilationUnit.getPackageDeclaration()
                .ifPresent(suppliersCompilationUnit::setPackageDeclaration);

        processorManager.importAllClassOrInterfaceType(suppliersClassDeclaration, componentClassDeclaration);

        Expression objectCreateExpression = proxyClassDeclaration.getMethods().stream()
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
                                .setScope(new NameExpr(proxyClassDeclaration.getNameAsString()))
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
                                                .setScope(new NameExpr(componentClassDeclaration.getNameAsString()))
                                )
                )
                .orElseGet(() ->
                        new ObjectCreationExpr()
                                .setType(proxyClassDeclaration.getNameAsString())
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
                );

        Expression getInstanceExpression;
        if (componentClassDeclaration.isAnnotationPresent(Singleton.class) || componentClassDeclaration.isAnnotationPresent(ApplicationScoped.class)) {
            ClassOrInterfaceDeclaration holderClassDeclaration = new ClassOrInterfaceDeclaration()
                    .setName("Holder")
                    .setModifiers(Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC);

            holderClassDeclaration.addFieldWithInitializer(
                            componentClassDeclaration.getNameAsString(),
                            "INSTANCE",
                            objectCreateExpression
                    )
                    .setModifiers(Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC, Modifier.Keyword.FINAL);

            suppliersClassDeclaration.addMember(holderClassDeclaration);

            getInstanceExpression = new FieldAccessExpr()
                    .setName("INSTANCE")
                    .setScope(new NameExpr("Holder"));

        } else if (componentClassDeclaration.isAnnotationPresent(RequestScoped.class) || componentClassDeclaration.isAnnotationPresent(SessionScoped.class) || componentClassDeclaration.isAnnotationPresent(TransactionScoped.class)) {
            String scopedAnnotationName = processorManager.getScopedAnnotationName(componentClassDeclaration)
                    .orElseThrow(() -> new InjectionProcessException(ANNOTATION_NOT_EXIST.bind(qualifiedName)));

            suppliersCompilationUnit.addImport(ReactorBeanScoped.class)
                    .addImport(Mono.class);

            getInstanceExpression = new MethodCallExpr()
                    .setName("get")
                    .addArgument(new ClassExpr().setType(componentClassDeclaration.getNameAsString()))
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
                                    producesMethodDeclaration.getTypeAsString(),
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

        MethodDeclaration getBeanSuppliers = new MethodDeclaration()
                .setName("getBeanSuppliers")
                .setModifiers(Modifier.Keyword.PUBLIC)
                .setType(beanSuppliersType)
                .addAnnotation(Override.class);

        BlockStmt staticInitializer = new BlockStmt()
                .addStatement(
                        new VariableDeclarationExpr()
                                .addVariable(
                                        new VariableDeclarator().setName("beanSupplier")
                                                .setType(BeanSupplier.class)
                                                .setInitializer(
                                                        new ObjectCreationExpr()
                                                                .setType(BeanSupplier.class)
                                                )
                                )
                );

        MethodCallExpr mapOf = new MethodCallExpr()
                .setName("of")
                .setScope(new NameExpr("Map"));

        componentClassDeclaration.getAnnotations()
                .forEach(annotationExpr ->
                        processorManager.getCompilationUnit(annotationExpr)
                                .flatMap(compilationUnit -> processorManager.getPublicAnnotationDeclaration(compilationUnit))
                                .ifPresent(annotationDeclaration -> {
                                    if (annotationDeclaration.getAnnotations().stream()
                                            .anyMatch(subAnnotationExpr -> processorManager.getQualifiedName(subAnnotationExpr).equals(Qualifier.class.getName()))) {
                                        mapOf.addArgument(new StringLiteralExpr(annotationDeclaration.getFullyQualifiedName().orElseGet(annotationDeclaration::getNameAsString)));
                                        mapOf.addArgument(qualifierToExpression(annotationExpr));
                                    }
                                })
                );

        staticInitializer.addStatement(
                new MethodCallExpr().setName("setQualifiers")
                        .addArgument(mapOf)
                        .setScope(new NameExpr("beanSupplier"))
        );

        staticInitializer.addStatement(
                new MethodCallExpr().setName("setSupplier")
                        .addArgument(
                                new LambdaExpr()
                                        .setEnclosingParameters(true)
                                        .setBody(new ExpressionStmt(getInstanceExpression))
                        )
                        .setScope(new NameExpr("beanSupplier"))
        );

        componentClassDeclaration.getAnnotationByClass(Priority.class)
                .ifPresent(annotation -> {
                    if (annotation.isNormalAnnotationExpr()) {
                        staticInitializer.addStatement(
                                new MethodCallExpr().setName("setPriority")
                                        .addArgument(annotation.asNormalAnnotationExpr().getPairs().get(0).getValue())
                                        .setScope(new NameExpr("beanSupplier"))
                        );
                    } else if (annotation.isSingleMemberAnnotationExpr()) {
                        staticInitializer.addStatement(
                                new MethodCallExpr().setName("setPriority")
                                        .addArgument(annotation.asSingleMemberAnnotationExpr().getMemberValue())
                                        .setScope(new NameExpr("beanSupplier"))
                        );
                    }
                });

        staticInitializer.addStatement(
                new MethodCallExpr().setName("put")
                        .addArgument(new StringLiteralExpr(qualifiedName))
                        .addArgument(new NameExpr("beanSupplier"))
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

        processorManager.getExtendedTypes(componentClassDeclaration)
                .forEach(extendedTypeName ->
                        staticInitializer.addStatement(
                                new MethodCallExpr().setName("put")
                                        .addArgument(new StringLiteralExpr(qualifiedName))
                                        .addArgument(new NameExpr("beanSupplier"))
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
                                        .addArgument(new NameExpr("beanSupplier"))
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
                    String prefix = methodTypeQualifiedName.replaceAll("\\.", "_");

                    MethodCallExpr mapOfProduces = new MethodCallExpr()
                            .setName("of")
                            .setScope(new NameExpr("Map"));

                    producesMethodDeclaration.getAnnotations()
                            .forEach(annotationExpr ->
                                    processorManager.getCompilationUnit(annotationExpr)
                                            .flatMap(compilationUnit -> processorManager.getPublicAnnotationDeclaration(compilationUnit))
                                            .ifPresent(annotationDeclaration -> {
                                                if (annotationDeclaration.getAnnotations().stream()
                                                        .anyMatch(subAnnotationExpr -> processorManager.getQualifiedName(subAnnotationExpr).equals(Qualifier.class.getName()))) {
                                                    mapOfProduces.addArgument(new StringLiteralExpr(annotationDeclaration.getFullyQualifiedName().orElseGet(annotationDeclaration::getNameAsString)));
                                                    mapOfProduces.addArgument(qualifierToExpression(annotationExpr));
                                                }
                                            })
                            );

                    Expression producesCreateExpression;
                    if (producesMethodDeclaration.isAnnotationPresent(Singleton.class) || producesMethodDeclaration.isAnnotationPresent(ApplicationScoped.class)) {
                        producesCreateExpression = new FieldAccessExpr()
                                .setName("INSTANCE")
                                .setScope(new NameExpr(prefix + "Holder" + componentClassDeclaration.getMethods().indexOf(producesMethodDeclaration)));
                    } else if (producesMethodDeclaration.isAnnotationPresent(RequestScoped.class) || producesMethodDeclaration.isAnnotationPresent(SessionScoped.class) || producesMethodDeclaration.isAnnotationPresent(TransactionScoped.class)) {
                        String scopedAnnotationName = processorManager.getScopedAnnotationName(producesMethodDeclaration)
                                .orElseThrow(() -> new InjectionProcessException(ANNOTATION_NOT_EXIST.bind(qualifiedName)));

                        suppliersCompilationUnit.addImport(ReactorBeanScoped.class)
                                .addImport(Mono.class);

                        producesCreateExpression = new MethodCallExpr()
                                .setName("get")
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
                                                    .setBody(new ExpressionStmt(producesCreateExpression))
                                    )
                                    .setScope(new NameExpr(prefix + "_beanSupplier"))
                    );

                    producesMethodDeclaration.getAnnotationByClass(Priority.class)
                            .ifPresent(annotation -> {
                                if (annotation.isNormalAnnotationExpr()) {
                                    staticInitializer.addStatement(
                                            new MethodCallExpr().setName("setPriority")
                                                    .addArgument(annotation.asNormalAnnotationExpr().getPairs().get(0).getValue())
                                                    .setScope(new NameExpr(prefix + "_beanSupplier"))
                                    );
                                } else if (annotation.isSingleMemberAnnotationExpr()) {
                                    staticInitializer.addStatement(
                                            new MethodCallExpr().setName("setPriority")
                                                    .addArgument(annotation.asSingleMemberAnnotationExpr().getMemberValue())
                                                    .setScope(new NameExpr(prefix + "_beanSupplier"))
                                    );
                                }
                            });
                    staticInitializer.addStatement(
                            new MethodCallExpr().setName("put")
                                    .addArgument(new StringLiteralExpr(methodTypeQualifiedName))
                                    .addArgument(new NameExpr(prefix + "_beanSupplier"))
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
                                                                .addArgument(new NameExpr(prefix + "_beanSupplier"))
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
                                                                .addArgument(new NameExpr(prefix + "_beanSupplier"))
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

        suppliersClassDeclaration.addMember(new InitializerDeclaration(true, staticInitializer));

        getBeanSuppliers.createBody()
                .addStatement(new ReturnStmt().setExpression(new NameExpr("beanSuppliers")));

        suppliersClassDeclaration.addMember(getBeanSuppliers);

        logger.info("{} suppliers class build success", qualifiedName);
        return suppliersCompilationUnit;
    }

    private Expression qualifierToExpression(AnnotationExpr qualifier) {
        MethodCallExpr mapOf = new MethodCallExpr()
                .setName("of")
                .setScope(new NameExpr("Map"));
        if (qualifier.isNormalAnnotationExpr()) {
            qualifier.asNormalAnnotationExpr().getPairs()
                    .forEach(pair -> {
                        mapOf.addArgument(new StringLiteralExpr(pair.getNameAsString()));
                        mapOf.addArgument(pair.getValue());
                    });
        } else if (qualifier.isSingleMemberAnnotationExpr()) {
            mapOf.addArgument(new StringLiteralExpr("value"));
            mapOf.addArgument(qualifier.asSingleMemberAnnotationExpr().getMemberValue());
        }
        return mapOf;
    }

    private MethodCallExpr getBeanGetMethodCallExpr(CompilationUnit proxyCompilationUnit, NodeWithAnnotations<?> annotations, ClassOrInterfaceType classOrInterfaceType) {
        MethodCallExpr mapOf = new MethodCallExpr()
                .setName("of")
                .setScope(new NameExpr("Map"));

        annotations.getAnnotations()
                .forEach(annotationExpr ->
                        processorManager.getCompilationUnit(annotationExpr)
                                .flatMap(compilationUnit -> processorManager.getPublicAnnotationDeclaration(compilationUnit))
                                .ifPresent(annotationDeclaration -> {
                                    if (annotationDeclaration.getAnnotations().stream()
                                            .anyMatch(subAnnotationExpr -> processorManager.getQualifiedName(subAnnotationExpr).equals(Qualifier.class.getName()))) {
                                        mapOf.addArgument(new StringLiteralExpr(annotationDeclaration.getFullyQualifiedName().orElseGet(annotationDeclaration::getNameAsString)));
                                        mapOf.addArgument(qualifierToExpression(annotationExpr));
                                    }
                                })
                );

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
