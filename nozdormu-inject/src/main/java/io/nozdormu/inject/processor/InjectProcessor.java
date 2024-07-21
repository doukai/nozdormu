package io.nozdormu.inject.processor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.VoidType;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.google.auto.service.AutoService;
import com.google.common.collect.Streams;
import io.nozdormu.common.ProcessorManager;
import io.nozdormu.spi.context.BeanContextLoader;
import io.nozdormu.spi.error.InjectionProcessException;
import io.nozdormu.spi.context.BeanContext;
import io.nozdormu.spi.context.ScopeInstanceFactory;
import jakarta.annotation.Generated;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.transaction.TransactionScoped;
import org.tinylog.Logger;
import reactor.core.publisher.Mono;

import javax.annotation.processing.*;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.nozdormu.spi.error.InjectionProcessErrorType.*;
import static javax.lang.model.SourceVersion.RELEASE_11;

@SupportedAnnotationTypes({
        "jakarta.inject.Singleton",
        "jakarta.enterprise.context.Dependent",
        "jakarta.enterprise.context.ApplicationScoped",
        "jakarta.enterprise.context.RequestScoped",
        "jakarta.enterprise.context.SessionScoped",
        "jakarta.transaction.TransactionScoped"
})
@SupportedSourceVersion(RELEASE_11)
@AutoService(Processor.class)
public class InjectProcessor extends AbstractProcessor {

    private final Set<ComponentProxyProcessor> componentProxyProcessors = new HashSet<>();
    private ProcessorManager processorManager;
    private final List<String> processed = new ArrayList<>();
    private int index = 0;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        ServiceLoader<ComponentProxyProcessor> loader = ServiceLoader.load(ComponentProxyProcessor.class, InjectProcessor.class.getClassLoader());
        loader.forEach(componentProxyProcessors::add);
        this.processorManager = new ProcessorManager(processingEnv, InjectProcessor.class.getClassLoader());
        for (ComponentProxyProcessor componentProxyProcessor : this.componentProxyProcessors) {
            Logger.debug("init {}", componentProxyProcessor.getClass().getName());
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
                .filter(typeElement -> !processed.contains(typeElement.getQualifiedName().toString()))
                .collect(Collectors.toList());

        if (typeElements.isEmpty()) {
            return false;
        } else {
            processed.addAll(typeElements.stream().map(typeElement -> typeElement.getQualifiedName().toString()).collect(Collectors.toList()));
        }

        processorManager.setRoundEnv(roundEnv);
        componentProxyProcessors
                .forEach(componentProxyProcessor -> {
                            Logger.debug("inProcess {}", componentProxyProcessor.getClass().getName());
                            componentProxyProcessor.inProcess();
                        }
                );

        List<CompilationUnit> componentProxyCompilationUnits = typeElements.stream()
                .map(this::buildComponentProxy)
                .collect(Collectors.toList());
        componentProxyCompilationUnits.forEach(compilationUnit -> processorManager.writeToFiler(compilationUnit));
        Logger.debug("all proxy class build success");

        CompilationUnit moduleContextCompilationUnit = buildModuleContext(
                typeElements.stream()
                        .flatMap(typeElement -> processorManager.getCompilationUnit(typeElement).stream())
                        .collect(Collectors.toList()),
                componentProxyCompilationUnits,
                index++
        );
        processorManager.writeToFiler(moduleContextCompilationUnit);
        Logger.debug("module context class build success");

        List<CompilationUnit> producesContextCompilationUnits = buildProducesContextStream(singletonSet, dependentSet, applicationScopedSet, requestScopedSet, sessionScopedSet, transactionScopedSet).collect(Collectors.toList());
        producesContextCompilationUnits.forEach(producesModuleCompilationUnit -> {
                    processorManager.writeToFiler(producesModuleCompilationUnit);
                    Logger.debug("produces context class build success");
                }
        );
        Logger.debug("all produces module class build success");

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
        ClassOrInterfaceDeclaration proxyClassDeclaration = new ClassOrInterfaceDeclaration()
                .addModifier(Modifier.Keyword.PUBLIC)
                .addExtendedType(componentClassDeclaration.getNameAsString())
                .setName(componentClassDeclaration.getNameAsString() + "_Proxy")
                .addAnnotation(new NormalAnnotationExpr().addPair("value", new StringLiteralExpr(getClass().getName())).setName(Generated.class.getSimpleName()));

        CompilationUnit proxyCompilationUnit = new CompilationUnit()
                .addType(proxyClassDeclaration)
                .addImport(Generated.class);

        componentCompilationUnit.getPackageDeclaration()
                .map(PackageDeclaration::clone)
                .ifPresent(proxyCompilationUnit::setPackageDeclaration);

        proxyClassDeclaration.setParentNode(proxyCompilationUnit);

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
                .map(fieldDeclaration -> (FieldDeclaration) fieldDeclaration.clone().setParentNode(componentClassDeclaration))
                .collect(Collectors.toList());

        privateFieldDeclarationList.forEach(proxyClassDeclaration::addMember);

        componentClassDeclaration.getConstructors().stream()
                .map(ConstructorDeclaration::clone)
                .forEach(constructorDeclaration -> {
                            constructorDeclaration.setParentNode(proxyClassDeclaration);
                            BlockStmt blockStmt = proxyClassDeclaration
                                    .addConstructor(Modifier.Keyword.PUBLIC)
                                    .setAnnotations(constructorDeclaration.getAnnotations())
                                    .setParameters(constructorDeclaration.getParameters())
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
                                                methodDeclaration.getParameters().forEach(constructorDeclaration::addParameter);
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
                        }
                );

        componentClassDeclaration.getAnnotations().stream()
                .filter(annotationExpr ->
                        proxyClassDeclaration.getAnnotations().stream()
                                .noneMatch(exist -> exist.getNameAsString().equals(annotationExpr.getNameAsString()))
                )
                .filter(annotationExpr -> {
                            String qualifiedName = processorManager.getQualifiedName(annotationExpr);
                            return !qualifiedName.equals(Singleton.class.getName()) &&
                                    !qualifiedName.equals(Dependent.class.getName()) &&
                                    !qualifiedName.equals(ApplicationScoped.class.getName()) &&
                                    !qualifiedName.equals(SessionScoped.class.getName()) &&
                                    !qualifiedName.equals(RequestScoped.class.getName()) &&
                                    !qualifiedName.equals(TransactionScoped.class.getName());
                        }
                )
                .map(AnnotationExpr::clone)
                .forEach(annotationExpr -> {
                            annotationExpr.setParentNode(proxyClassDeclaration);
                            proxyClassDeclaration.addAnnotation(annotationExpr);
                        }
                );

        componentProxyProcessors
                .forEach(componentProxyProcessor -> {
                            Logger.debug("processComponentProxy {}", componentProxyProcessor.getClass().getName());
                            componentProxyProcessor.processComponentProxy(componentCompilationUnit, componentClassDeclaration, proxyCompilationUnit, proxyClassDeclaration);
                        }
                );
        Logger.info("{} proxy class build success", componentClassDeclaration.getNameAsString());
        return proxyCompilationUnit;
    }

    private CompilationUnit buildModuleContext(List<CompilationUnit> componentCompilationUnits, List<CompilationUnit> componentProxyCompilationUnits, int index) {
        ClassOrInterfaceDeclaration contextClassDeclaration = new ClassOrInterfaceDeclaration()
                .setPublic(true)
                .setName(processorManager.getRootPackageName().replaceAll("\\.", "_") + (index == 0 ? "" : index) + "_Context")
                .addAnnotation(
                        new SingleMemberAnnotationExpr()
                                .setMemberValue(new ClassExpr().setType(BeanContextLoader.class))
                                .setName(AutoService.class.getSimpleName())
                )
                .addAnnotation(
                        new NormalAnnotationExpr()
                                .addPair("value", new StringLiteralExpr(getClass().getName()))
                                .setName(Generated.class.getSimpleName())
                )
                .addImplementedType(BeanContextLoader.class);

        CompilationUnit contextCompilationUnit = new CompilationUnit()
                .addType(contextClassDeclaration)
                .addImport(AutoService.class)
                .addImport(Generated.class)
                .addImport(BeanContextLoader.class)
                .addImport(BeanContext.class);

        MethodDeclaration loadMethod = new MethodDeclaration()
                .setName("load")
                .setModifiers(Modifier.Keyword.PUBLIC)
                .setType(new VoidType())
                .addAnnotation(Override.class);

        contextClassDeclaration.addMember(loadMethod);
        BlockStmt staticInitializer = loadMethod.createBody();

        componentCompilationUnits.forEach(compilationUnit ->
                processorManager.getPublicClassOrInterfaceDeclaration(compilationUnit)
                        .ifPresent(classOrInterfaceDeclaration -> {
                                    String qualifiedName = processorManager.getQualifiedName(classOrInterfaceDeclaration);
                                    ClassOrInterfaceDeclaration proxyClassOrInterfaceDeclaration = componentProxyCompilationUnits.stream()
                                            .map(processorManager::getPublicClassOrInterfaceDeclarationOrError)
                                            .filter(proxyClassDeclaration ->
                                                    proxyClassDeclaration.getExtendedTypes().stream()
                                                            .anyMatch(classOrInterfaceType ->
                                                                    processorManager.getQualifiedName(classOrInterfaceType).equals(qualifiedName)
                                                            )
                                            )
                                            .findFirst()
                                            .orElseThrow(() -> new RuntimeException(qualifiedName + "_Proxy not found"));

                                    Optional<Expression> nameExpr = classOrInterfaceDeclaration.getAnnotationByClass(Named.class)
                                            .flatMap(processorManager::findAnnotationValue)
                                            .map(expression -> {
                                                        if (expression.isStringLiteralExpr()) {
                                                            return expression.asStringLiteralExpr();
                                                        } else {
                                                            expression.findAll(NameExpr.class).stream()
                                                                    .map(expr -> processorManager.calculateType(expr))
                                                                    .filter(ResolvedType::isReferenceType)
                                                                    .map(ResolvedType::asReferenceType)
                                                                    .map(ResolvedReferenceType::getQualifiedName)
                                                                    .forEach(contextCompilationUnit::addImport);
                                                            if (expression.isNameExpr() && processorManager.getResolvedDeclaration(expression.asNameExpr()).isField()) {
                                                                return new FieldAccessExpr()
                                                                        .setName(expression.asNameExpr().getName())
                                                                        .setScope(new NameExpr(processorManager.getResolvedDeclaration(expression.asNameExpr()).asField().declaringType().getQualifiedName()));
                                                            }
                                                            return expression;
                                                        }
                                                    }
                                            );

                                    Optional<StringLiteralExpr> defaultExpr = classOrInterfaceDeclaration.getAnnotationByClass(Default.class)
                                            .map(annotationExpr -> new StringLiteralExpr(Default.class.getName()));

                                    Optional<Expression> priorityExpr = classOrInterfaceDeclaration.getAnnotationByClass(Priority.class)
                                            .flatMap(processorManager::findAnnotationValue)
                                            .map(expression -> {
                                                        if (expression.isIntegerLiteralExpr()) {
                                                            return expression.asIntegerLiteralExpr();
                                                        } else {
                                                            expression.findAll(NameExpr.class).stream()
                                                                    .map(expr -> processorManager.calculateType(expr))
                                                                    .filter(ResolvedType::isReferenceType)
                                                                    .map(ResolvedType::asReferenceType)
                                                                    .map(ResolvedReferenceType::getQualifiedName)
                                                                    .forEach(contextCompilationUnit::addImport);
                                                            if (expression.isNameExpr() && processorManager.getResolvedDeclaration(expression.asNameExpr()).isField()) {
                                                                return new FieldAccessExpr()
                                                                        .setName(expression.asNameExpr().getName())
                                                                        .setScope(new NameExpr(processorManager.getResolvedDeclaration(expression.asNameExpr()).asField().declaringType().getQualifiedName()));
                                                            }
                                                            return expression;
                                                        }
                                                    }
                                            );

                                    if (classOrInterfaceDeclaration.isAnnotationPresent(Singleton.class) || classOrInterfaceDeclaration.isAnnotationPresent(ApplicationScoped.class)) {
                                        ClassOrInterfaceDeclaration holderClassDeclaration = new ClassOrInterfaceDeclaration()
                                                .setName(qualifiedName.replaceAll("\\.", "_") + "Holder")
                                                .setModifiers(Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC);

                                        Expression objectCreateExpression = proxyClassOrInterfaceDeclaration.getMethods().stream()
                                                .filter(methodDeclaration -> methodDeclaration.isAnnotationPresent(Produces.class))
                                                .filter(methodDeclaration -> processorManager.getQualifiedName(methodDeclaration.getType()).equals(qualifiedName + "_Proxy"))
                                                .findFirst()
                                                .map(methodDeclaration ->
                                                        (Expression) new MethodCallExpr()
                                                                .setName(methodDeclaration.getName())
                                                                .setArguments(
                                                                        methodDeclaration.getParameters().stream()
                                                                                .map(parameter -> getBeanGetMethodCallExpr(parameter, contextCompilationUnit, parameter.getType().asClassOrInterfaceType()))
                                                                                .map(methodCallExpr -> (Expression) methodCallExpr)
                                                                                .collect(Collectors.toCollection(NodeList::new))
                                                                )
                                                                .setScope(new NameExpr(qualifiedName + "_Proxy"))
                                                )
                                                .orElseGet(() ->
                                                        new ObjectCreationExpr()
                                                                .setType(qualifiedName + "_Proxy")
                                                                .setArguments(
                                                                        proxyClassOrInterfaceDeclaration.getConstructors().stream()
                                                                                .findFirst()
                                                                                .map(constructorDeclaration ->
                                                                                        constructorDeclaration.getParameters().stream()
                                                                                                .map(parameter -> getBeanGetMethodCallExpr(parameter, contextCompilationUnit, parameter.getType().asClassOrInterfaceType()))
                                                                                                .map(methodCallExpr -> (Expression) methodCallExpr)
                                                                                                .collect(Collectors.toCollection(NodeList::new))
                                                                                )
                                                                                .orElseGet(NodeList::new)
                                                                )
                                                );

                                        holderClassDeclaration.addFieldWithInitializer(
                                                        qualifiedName,
                                                        "INSTANCE",
                                                        objectCreateExpression
                                                )
                                                .setModifiers(Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC, Modifier.Keyword.FINAL);

                                        contextClassDeclaration.addMember(holderClassDeclaration);
                                        addPutTypeStatement(staticInitializer, qualifiedName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, nameExpr.orElse(null), priorityExpr.orElse(null), defaultExpr.isPresent(), true);
                                        processorManager.getExtendedTypes(classOrInterfaceDeclaration)
                                                .filter(extendedTypeName -> processorManager.getClassOrInterfaceDeclarationOrError(extendedTypeName).hasModifier(Modifier.Keyword.PUBLIC))
                                                .forEach(extendedTypeName -> addPutTypeStatement(staticInitializer, extendedTypeName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, nameExpr.orElse(null), priorityExpr.orElse(null), defaultExpr.isPresent(), true));

                                        processorManager.getImplementedTypes(classOrInterfaceDeclaration)
                                                .filter(implementedTypeName -> processorManager.getClassOrInterfaceDeclarationOrError(implementedTypeName).hasModifier(Modifier.Keyword.PUBLIC))
                                                .forEach(implementedTypeName -> addPutTypeStatement(staticInitializer, implementedTypeName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, nameExpr.orElse(null), priorityExpr.orElse(null), defaultExpr.isPresent(), true));
                                    } else {
                                        addPutTypeStatement(staticInitializer, qualifiedName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, nameExpr.orElse(null), priorityExpr.orElse(null), defaultExpr.isPresent(), false);
                                        processorManager.getExtendedTypes(classOrInterfaceDeclaration)
                                                .filter(extendedTypeName -> processorManager.getClassOrInterfaceDeclarationOrError(extendedTypeName).hasModifier(Modifier.Keyword.PUBLIC))
                                                .forEach(extendedTypeName -> addPutTypeStatement(staticInitializer, extendedTypeName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, nameExpr.orElse(null), priorityExpr.orElse(null), defaultExpr.isPresent(), false));

                                        processorManager.getImplementedTypes(classOrInterfaceDeclaration)
                                                .filter(implementedTypeName -> processorManager.getClassOrInterfaceDeclarationOrError(implementedTypeName).hasModifier(Modifier.Keyword.PUBLIC))
                                                .forEach(implementedTypeName -> addPutTypeStatement(staticInitializer, implementedTypeName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, nameExpr.orElse(null), priorityExpr.orElse(null), defaultExpr.isPresent(), false));
                                    }
                                }
                        )
        );
        componentProxyProcessors.forEach(componentProxyProcessor -> {
                    Logger.debug("processModuleContext {}", componentProxyProcessor.getClass().getName());
                    componentProxyProcessor.processModuleContext(contextCompilationUnit, contextClassDeclaration, staticInitializer);
                }
        );
        Logger.info("{} module context class build success", contextClassDeclaration.getNameAsString());
        return contextCompilationUnit;
    }

    private void addPutTypeStatement(BlockStmt staticInitializer, String putClassQualifiedName, CompilationUnit contextCompilationUnit, ClassOrInterfaceDeclaration classOrInterfaceDeclaration, ClassOrInterfaceDeclaration proxyClassOrInterfaceDeclaration, Expression nameExpr, Expression priorityExpr, boolean isDefault, boolean isSingleton) {
        String qualifiedName = processorManager.getQualifiedName(classOrInterfaceDeclaration);
        Expression supplierExpression;
        if (isSingleton) {
            supplierExpression = new LambdaExpr()
                    .setEnclosingParameters(true)
                    .setBody(
                            new ExpressionStmt()
                                    .setExpression(
                                            new FieldAccessExpr()
                                                    .setName("INSTANCE")
                                                    .setScope(new NameExpr(qualifiedName.replaceAll("\\.", "_") + "Holder"))
                                    )
                    );
        } else {
            Expression objectCreateExpression = proxyClassOrInterfaceDeclaration.getMethods().stream()
                    .filter(methodDeclaration -> methodDeclaration.isAnnotationPresent(Produces.class))
                    .filter(methodDeclaration -> processorManager.getQualifiedName(methodDeclaration.getType()).equals(qualifiedName + "_Proxy"))
                    .findFirst()
                    .map(methodDeclaration ->
                            (Expression) new MethodCallExpr()
                                    .setName(methodDeclaration.getName())
                                    .setArguments(
                                            methodDeclaration.getParameters().stream()
                                                    .map(parameter -> getBeanGetMethodCallExpr(parameter, contextCompilationUnit, parameter.getType().asClassOrInterfaceType()))
                                                    .map(methodCallExpr -> (Expression) methodCallExpr)
                                                    .collect(Collectors.toCollection(NodeList::new))
                                    )
                                    .setScope(new NameExpr(qualifiedName + "_Proxy"))
                    )
                    .orElseGet(() ->
                            new ObjectCreationExpr()
                                    .setType(qualifiedName + "_Proxy")
                                    .setArguments(
                                            proxyClassOrInterfaceDeclaration.getConstructors().stream()
                                                    .findFirst()
                                                    .map(constructorDeclaration ->
                                                            constructorDeclaration.getParameters().stream()
                                                                    .map(parameter -> getBeanGetMethodCallExpr(parameter, contextCompilationUnit, parameter.getType().asClassOrInterfaceType()))
                                                                    .map(methodCallExpr -> (Expression) methodCallExpr)
                                                                    .collect(Collectors.toCollection(NodeList::new))
                                                    )
                                                    .orElseGet(NodeList::new)
                                    )
                    );

            String scopedAnnotationName = processorManager.getScopedAnnotationName(classOrInterfaceDeclaration).orElse(null);
            if (scopedAnnotationName != null) {
                contextCompilationUnit
                        .addImport(ScopeInstanceFactory.class)
                        .addImport(Mono.class);

                MethodCallExpr getScopeInstanceFactory = new MethodCallExpr()
                        .setName("get")
                        .setScope(new NameExpr("BeanContext"))
                        .addArgument(new ClassExpr().setType(ScopeInstanceFactory.class))
                        .addArgument(new MethodCallExpr("getName").setScope(new ClassExpr().setType(scopedAnnotationName)));

                supplierExpression = new LambdaExpr()
                        .setEnclosingParameters(true)
                        .setBody(
                                new ExpressionStmt()
                                        .setExpression(
                                                new MethodCallExpr()
                                                        .setName("get")
                                                        .addArgument(new ClassExpr().setType(qualifiedName))
                                                        .addArgument(
                                                                new LambdaExpr()
                                                                        .setEnclosingParameters(true)
                                                                        .setBody(
                                                                                new ExpressionStmt()
                                                                                        .setExpression(
                                                                                                new MethodCallExpr()
                                                                                                        .setName("just")
                                                                                                        .setScope(new NameExpr().setName("Mono"))
                                                                                                        .addArgument(objectCreateExpression)
                                                                                        )
                                                                        )
                                                        )
                                                        .setScope(getScopeInstanceFactory)
                                        )
                        );
            } else {
                supplierExpression = new LambdaExpr()
                        .setEnclosingParameters(true)
                        .setBody(
                                new ExpressionStmt()
                                        .setExpression(objectCreateExpression)
                        );
            }
        }
        if (nameExpr != null && priorityExpr != null && isDefault) {
            staticInitializer.addStatement(
                    new MethodCallExpr()
                            .setName("put")
                            .addArgument(new ClassExpr().setType(putClassQualifiedName))
                            .addArgument(nameExpr)
                            .addArgument(priorityExpr)
                            .addArgument(new BooleanLiteralExpr(true))
                            .addArgument(supplierExpression)
                            .setScope(new NameExpr("BeanContext"))
            );
        } else if (nameExpr != null && priorityExpr != null) {
            staticInitializer.addStatement(
                    new MethodCallExpr()
                            .setName("put")
                            .addArgument(new ClassExpr().setType(putClassQualifiedName))
                            .addArgument(nameExpr)
                            .addArgument(priorityExpr)
                            .addArgument(supplierExpression)
                            .setScope(new NameExpr("BeanContext"))
            );
        } else if (nameExpr != null && isDefault) {
            staticInitializer.addStatement(
                    new MethodCallExpr()
                            .setName("put")
                            .addArgument(new ClassExpr().setType(putClassQualifiedName))
                            .addArgument(nameExpr)
                            .addArgument(new BooleanLiteralExpr(true))
                            .addArgument(supplierExpression)
                            .setScope(new NameExpr("BeanContext"))
            );
        } else if (priorityExpr != null && isDefault) {
            staticInitializer.addStatement(
                    new MethodCallExpr()
                            .setName("put")
                            .addArgument(new ClassExpr().setType(putClassQualifiedName))
                            .addArgument(priorityExpr)
                            .addArgument(new BooleanLiteralExpr(true))
                            .addArgument(supplierExpression)
                            .setScope(new NameExpr("BeanContext"))
            );
        } else if (nameExpr != null) {
            staticInitializer.addStatement(
                    new MethodCallExpr()
                            .setName("put")
                            .addArgument(new ClassExpr().setType(putClassQualifiedName))
                            .addArgument(nameExpr)
                            .addArgument(supplierExpression)
                            .setScope(new NameExpr("BeanContext"))
            );
        } else if (priorityExpr != null) {
            staticInitializer.addStatement(
                    new MethodCallExpr()
                            .setName("put")
                            .addArgument(new ClassExpr().setType(putClassQualifiedName))
                            .addArgument(priorityExpr)
                            .addArgument(supplierExpression)
                            .setScope(new NameExpr("BeanContext"))
            );
        } else if (isDefault) {
            staticInitializer.addStatement(
                    new MethodCallExpr()
                            .setName("put")
                            .addArgument(new ClassExpr().setType(putClassQualifiedName))
                            .addArgument(new BooleanLiteralExpr(true))
                            .addArgument(supplierExpression)
                            .setScope(new NameExpr("BeanContext"))
            );
        } else {
            staticInitializer.addStatement(
                    new MethodCallExpr()
                            .setName("put")
                            .addArgument(new ClassExpr().setType(putClassQualifiedName))
                            .addArgument(supplierExpression)
                            .setScope(new NameExpr("BeanContext"))
            );
        }
    }

    private MethodCallExpr getBeanGetMethodCallExpr(NodeWithAnnotations<?> nodeWithAnnotations, CompilationUnit compilationUnit, ClassOrInterfaceType classOrInterfaceType) {
        Optional<Expression> nameExpr = nodeWithAnnotations.getAnnotationByClass(Default.class)
                .map(annotationExpr -> (Expression) new StringLiteralExpr(Default.class.getName()))
                .or(() ->
                        nodeWithAnnotations.getAnnotationByClass(Named.class)
                                .flatMap(processorManager::findAnnotationValue)
                                .map(expression -> {
                                            if (expression.isStringLiteralExpr()) {
                                                return expression.asStringLiteralExpr();
                                            } else {
                                                expression.findAll(NameExpr.class).stream()
                                                        .map(expr -> processorManager.calculateType(expr))
                                                        .filter(ResolvedType::isReferenceType)
                                                        .map(ResolvedType::asReferenceType)
                                                        .map(ResolvedReferenceType::getQualifiedName)
                                                        .forEach(compilationUnit::addImport);
                                                if (expression.isNameExpr() && processorManager.getResolvedDeclaration(expression.asNameExpr()).isField()) {
                                                    return new FieldAccessExpr()
                                                            .setName(expression.asNameExpr().getName())
                                                            .setScope(new NameExpr(processorManager.getResolvedDeclaration(expression.asNameExpr()).asField().declaringType().getQualifiedName()));
                                                }
                                                return expression;
                                            }
                                        }
                                )
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
            compilationUnit.addImport(Provider.class);
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
            compilationUnit.addImport(Instance.class);
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
        nameExpr.ifPresent(methodCallExpr::addArgument);
        return methodCallExpr;
    }

    private Stream<CompilationUnit> buildProducesContextStream(Set<? extends Element> singletonSet,
                                                               Set<? extends Element> dependentSet,
                                                               Set<? extends Element> applicationScopedSet,
                                                               Set<? extends Element> requestScopedSet,
                                                               Set<? extends Element> sessionScopedSet,
                                                               Set<? extends Element> transactionScopedSet) {

        return Streams.concat(singletonSet.stream(), dependentSet.stream(), applicationScopedSet.stream(), requestScopedSet.stream(), sessionScopedSet.stream(), transactionScopedSet.stream())
                .filter(element -> element.getAnnotation(Generated.class) == null)
                .filter(element -> element.getKind().isClass())
                .map(element -> (TypeElement) element)
                .map(typeElement -> processorManager.getCompilationUnitOrError(typeElement))
                .filter(compilationUnit ->
                        processorManager.getPublicClassOrInterfaceDeclarationOrError(compilationUnit).getMethods().stream()
                                .anyMatch(methodDeclaration -> methodDeclaration.isAnnotationPresent(Produces.class))
                )
                .map(compilationUnit -> {
                            ClassOrInterfaceDeclaration classOrInterfaceDeclaration = processorManager.getPublicClassOrInterfaceDeclarationOrError(compilationUnit);
                            ClassOrInterfaceDeclaration moduleContextClassDeclaration = new ClassOrInterfaceDeclaration()
                                    .setPublic(true)
                                    .setName(classOrInterfaceDeclaration.getNameAsString() + "_Context")
                                    .addAnnotation(
                                            new SingleMemberAnnotationExpr()
                                                    .setMemberValue(new ClassExpr().setType(BeanContextLoader.class))
                                                    .setName(AutoService.class.getSimpleName())
                                    )
                                    .addAnnotation(
                                            new NormalAnnotationExpr()
                                                    .addPair("value", new StringLiteralExpr(getClass().getName()))
                                                    .setName(Generated.class.getSimpleName())
                                    )
                                    .addImplementedType(BeanContextLoader.class);

                            CompilationUnit moduleContextCompilationUnit = new CompilationUnit()
                                    .addType(moduleContextClassDeclaration)
                                    .addImport(AutoService.class)
                                    .addImport(Generated.class)
                                    .addImport(BeanContextLoader.class)
                                    .addImport(BeanContext.class);

                            processorManager.importAllClassOrInterfaceType(moduleContextClassDeclaration, classOrInterfaceDeclaration);

                            compilationUnit.getPackageDeclaration()
                                    .ifPresent(packageDeclaration -> moduleContextCompilationUnit.setPackageDeclaration(packageDeclaration.getNameAsString()));

                            MethodDeclaration loadMethod = new MethodDeclaration()
                                    .setName("load")
                                    .setModifiers(Modifier.Keyword.PUBLIC)
                                    .setType(new VoidType())
                                    .addAnnotation(Override.class);

                            moduleContextClassDeclaration.addMember(loadMethod);
                            BlockStmt staticInitializer = loadMethod.createBody();

                            classOrInterfaceDeclaration.getMethods().stream()
                                    .filter(methodDeclaration -> methodDeclaration.isAnnotationPresent(Produces.class))
                                    .forEach(producesMethodDeclaration -> {
                                                String qualifiedName = processorManager.getQualifiedName(producesMethodDeclaration);

                                                Optional<Expression> nameExpr = producesMethodDeclaration.getAnnotationByClass(Named.class)
                                                        .flatMap(processorManager::findAnnotationValue)
                                                        .map(expression -> {
                                                                    if (expression.isStringLiteralExpr()) {
                                                                        return expression.asStringLiteralExpr();
                                                                    } else {
                                                                        expression.findAll(NameExpr.class).stream()
                                                                                .map(expr -> processorManager.calculateType(expr))
                                                                                .filter(ResolvedType::isReferenceType)
                                                                                .map(ResolvedType::asReferenceType)
                                                                                .map(ResolvedReferenceType::getQualifiedName)
                                                                                .forEach(moduleContextCompilationUnit::addImport);
                                                                        if (expression.isNameExpr() && processorManager.getResolvedDeclaration(expression.asNameExpr()).isField()) {
                                                                            return new FieldAccessExpr()
                                                                                    .setName(expression.asNameExpr().getName())
                                                                                    .setScope(new NameExpr(processorManager.getResolvedDeclaration(expression.asNameExpr()).asField().declaringType().getQualifiedName()));
                                                                        }
                                                                        return expression;
                                                                    }
                                                                }
                                                        );

                                                Optional<StringLiteralExpr> defaultExpr = producesMethodDeclaration.getAnnotationByClass(Default.class)
                                                        .map(annotationExpr -> new StringLiteralExpr(Default.class.getName()));

                                                Optional<Expression> priorityExpr = classOrInterfaceDeclaration.getAnnotationByClass(Priority.class)
                                                        .flatMap(processorManager::findAnnotationValue)
                                                        .map(expression -> {
                                                                    if (expression.isIntegerLiteralExpr()) {
                                                                        return expression.asIntegerLiteralExpr();
                                                                    } else {
                                                                        expression.findAll(NameExpr.class).stream()
                                                                                .map(expr -> processorManager.calculateType(expr))
                                                                                .filter(ResolvedType::isReferenceType)
                                                                                .map(ResolvedType::asReferenceType)
                                                                                .map(ResolvedReferenceType::getQualifiedName)
                                                                                .forEach(moduleContextCompilationUnit::addImport);
                                                                        if (expression.isNameExpr() && processorManager.getResolvedDeclaration(expression.asNameExpr()).isField()) {
                                                                            return new FieldAccessExpr()
                                                                                    .setName(expression.asNameExpr().getName())
                                                                                    .setScope(new NameExpr(processorManager.getResolvedDeclaration(expression.asNameExpr()).asField().declaringType().getQualifiedName()));
                                                                        }
                                                                        return expression;
                                                                    }
                                                                }
                                                        );

                                                if (producesMethodDeclaration.isAnnotationPresent(Singleton.class) || producesMethodDeclaration.isAnnotationPresent(ApplicationScoped.class)) {
                                                    ClassOrInterfaceDeclaration holderClassOrInterfaceDeclaration = new ClassOrInterfaceDeclaration();
                                                    holderClassOrInterfaceDeclaration
                                                            .setName(qualifiedName.replaceAll("\\.", "_") + "Holder")
                                                            .setModifiers(Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC)
                                                            .addFieldWithInitializer(
                                                                    producesMethodDeclaration.getType(),
                                                                    "INSTANCE",
                                                                    new MethodCallExpr()
                                                                            .setName(producesMethodDeclaration.getName())
                                                                            .setArguments(
                                                                                    producesMethodDeclaration.getParameters().stream()
                                                                                            .map(parameter -> getBeanGetMethodCallExpr(parameter, moduleContextCompilationUnit, parameter.getType().asClassOrInterfaceType()))
                                                                                            .map(methodCallExpr -> (Expression) methodCallExpr)
                                                                                            .collect(Collectors.toCollection(NodeList::new))
                                                                            )
                                                                            .setScope(
                                                                                    new MethodCallExpr()
                                                                                            .setName("get")
                                                                                            .setScope(new NameExpr().setName("BeanContext"))
                                                                                            .addArgument(new ClassExpr().setType(processorManager.getQualifiedName(classOrInterfaceDeclaration)))
                                                                            )
                                                            )
                                                            .setModifiers(Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC, Modifier.Keyword.FINAL);

                                                    moduleContextClassDeclaration.addMember(holderClassOrInterfaceDeclaration);

                                                    addPutTypeProducerStatement(staticInitializer, qualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, nameExpr.orElse(null), priorityExpr.orElse(null), defaultExpr.isPresent(), true);
                                                    processorManager.getNodeReturnResolvedReferenceType(producesMethodDeclaration)
                                                            .filter(resolvedReferenceType -> !resolvedReferenceType.getQualifiedName().equals(processorManager.getQualifiedName(producesMethodDeclaration.getType())))
                                                            .forEach(resolvedReferenceType ->
                                                                    addPutTypeProducerStatement(staticInitializer, qualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, nameExpr.orElse(null), priorityExpr.orElse(null), defaultExpr.isPresent(), true)
                                                            );

                                                    processorManager.getClassOrInterfaceDeclaration(qualifiedName)
                                                            .ifPresent(returnTypeClassOrInterfaceDeclaration -> {
                                                                        processorManager.getExtendedTypes(returnTypeClassOrInterfaceDeclaration)
                                                                                .filter(extendedTypeName -> processorManager.getClassOrInterfaceDeclarationOrError(extendedTypeName).hasModifier(Modifier.Keyword.PUBLIC))
                                                                                .forEach(extendedTypeName -> {
                                                                                            addPutTypeProducerStatement(staticInitializer, extendedTypeName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, nameExpr.orElse(null), priorityExpr.orElse(null), defaultExpr.isPresent(), true);
                                                                                        }
                                                                                );

                                                                        processorManager.getImplementedTypes(returnTypeClassOrInterfaceDeclaration)
                                                                                .filter(implementedTypeName -> processorManager.getClassOrInterfaceDeclarationOrError(implementedTypeName).hasModifier(Modifier.Keyword.PUBLIC))
                                                                                .forEach(implementedTypeName -> {
                                                                                            addPutTypeProducerStatement(staticInitializer, implementedTypeName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, nameExpr.orElse(null), priorityExpr.orElse(null), defaultExpr.isPresent(), true);
                                                                                        }
                                                                                );

                                                                    }
                                                            );
                                                } else {
                                                    addPutTypeProducerStatement(staticInitializer, qualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, nameExpr.orElse(null), priorityExpr.orElse(null), defaultExpr.isPresent(), false);

                                                    processorManager.getClassOrInterfaceDeclaration(qualifiedName)
                                                            .ifPresent(returnTypeClassOrInterfaceDeclaration -> {
                                                                        processorManager.getExtendedTypes(returnTypeClassOrInterfaceDeclaration)
                                                                                .filter(extendedTypeName -> processorManager.getClassOrInterfaceDeclarationOrError(extendedTypeName).hasModifier(Modifier.Keyword.PUBLIC))
                                                                                .forEach(extendedTypeName -> {
                                                                                            addPutTypeProducerStatement(staticInitializer, extendedTypeName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, nameExpr.orElse(null), priorityExpr.orElse(null), defaultExpr.isPresent(), false);
                                                                                        }
                                                                                );

                                                                        processorManager.getImplementedTypes(returnTypeClassOrInterfaceDeclaration)
                                                                                .filter(implementedTypeName -> processorManager.getClassOrInterfaceDeclarationOrError(implementedTypeName).hasModifier(Modifier.Keyword.PUBLIC))
                                                                                .forEach(implementedTypeName -> {
                                                                                            addPutTypeProducerStatement(staticInitializer, implementedTypeName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, nameExpr.orElse(null), priorityExpr.orElse(null), defaultExpr.isPresent(), false);
                                                                                        }
                                                                                );
                                                                    }
                                                            );
                                                }
                                            }
                                    );
                            return moduleContextCompilationUnit;
                        }
                );
    }

    private void addPutTypeProducerStatement(BlockStmt staticInitializer, String putClassQualifiedName, CompilationUnit moduleContextCompilationUnit, ClassOrInterfaceDeclaration classOrInterfaceDeclaration, MethodDeclaration methodDeclaration, Expression nameExpr, Expression priorityExpr, boolean isDefault, boolean isSingleton) {
        String qualifiedName = processorManager.getQualifiedName(methodDeclaration);

        Expression supplierExpression;
        if (isSingleton) {
            supplierExpression = new LambdaExpr()
                    .setEnclosingParameters(true)
                    .setBody(
                            new ExpressionStmt()
                                    .setExpression(
                                            new FieldAccessExpr()
                                                    .setName("INSTANCE")
                                                    .setScope(new NameExpr(qualifiedName.replaceAll("\\.", "_") + "Holder"))
                                    )
                    );
        } else {
            Expression expression = new MethodCallExpr()
                    .setName(methodDeclaration.getName())
                    .setArguments(
                            methodDeclaration.getParameters().stream()
                                    .map(parameter -> getBeanGetMethodCallExpr(parameter, moduleContextCompilationUnit, parameter.getType().asClassOrInterfaceType()))
                                    .map(methodCallExpr -> (Expression) methodCallExpr)
                                    .collect(Collectors.toCollection(NodeList::new))
                    )
                    .setScope(
                            new MethodCallExpr()
                                    .setName("get")
                                    .setScope(new NameExpr().setName("BeanContext"))
                                    .addArgument(new ClassExpr().setType(processorManager.getQualifiedName(classOrInterfaceDeclaration)))
                    );

            String scopedAnnotationName = processorManager.getScopedAnnotationName(methodDeclaration).orElse(null);
            if (scopedAnnotationName != null) {
                moduleContextCompilationUnit.addImport(ScopeInstanceFactory.class)
                        .addImport(Mono.class);

                if (!processorManager.getQualifiedName(methodDeclaration.getType()).equals(Mono.class.getName())) {
                    moduleContextCompilationUnit.addImport(Mono.class);
                    expression = new MethodCallExpr()
                            .setName("just")
                            .setScope(new NameExpr().setName("Mono"))
                            .addArgument(expression);
                }

                MethodCallExpr getScopeInstanceFactory = new MethodCallExpr()
                        .setName("get")
                        .setScope(new NameExpr("BeanContext"))
                        .addArgument(new ClassExpr().setType(ScopeInstanceFactory.class))
                        .addArgument(new MethodCallExpr("getName").setScope(new ClassExpr().setType(scopedAnnotationName)));

                supplierExpression = new LambdaExpr()
                        .setEnclosingParameters(true)
                        .setBody(
                                new ExpressionStmt()
                                        .setExpression(
                                                new MethodCallExpr()
                                                        .setName("get")
                                                        .addArgument(new ClassExpr().setType(qualifiedName))
                                                        .addArgument(
                                                                new LambdaExpr()
                                                                        .setEnclosingParameters(true)
                                                                        .setBody(new ExpressionStmt().setExpression(expression))
                                                        )
                                                        .setScope(getScopeInstanceFactory)
                                        )
                        );
            } else {
                supplierExpression = new LambdaExpr()
                        .setEnclosingParameters(true)
                        .setBody(new ExpressionStmt().setExpression(expression));
            }
        }
        if (nameExpr != null && priorityExpr != null && isDefault) {
            staticInitializer.addStatement(
                    new MethodCallExpr()
                            .setName("put")
                            .addArgument(new ClassExpr().setType(putClassQualifiedName))
                            .addArgument(nameExpr)
                            .addArgument(priorityExpr)
                            .addArgument(new BooleanLiteralExpr(true))
                            .addArgument(supplierExpression)
                            .setScope(new NameExpr("BeanContext"))
            );
        } else if (nameExpr != null && priorityExpr != null) {
            staticInitializer.addStatement(
                    new MethodCallExpr()
                            .setName("put")
                            .addArgument(new ClassExpr().setType(putClassQualifiedName))
                            .addArgument(nameExpr)
                            .addArgument(priorityExpr)
                            .addArgument(supplierExpression)
                            .setScope(new NameExpr("BeanContext"))
            );
        } else if (nameExpr != null && isDefault) {
            staticInitializer.addStatement(
                    new MethodCallExpr()
                            .setName("put")
                            .addArgument(new ClassExpr().setType(putClassQualifiedName))
                            .addArgument(nameExpr)
                            .addArgument(new BooleanLiteralExpr(true))
                            .addArgument(supplierExpression)
                            .setScope(new NameExpr("BeanContext"))
            );
        } else if (priorityExpr != null && isDefault) {
            staticInitializer.addStatement(
                    new MethodCallExpr()
                            .setName("put")
                            .addArgument(new ClassExpr().setType(putClassQualifiedName))
                            .addArgument(priorityExpr)
                            .addArgument(new BooleanLiteralExpr(true))
                            .addArgument(supplierExpression)
                            .setScope(new NameExpr("BeanContext"))
            );
        } else if (nameExpr != null) {
            staticInitializer.addStatement(
                    new MethodCallExpr()
                            .setName("put")
                            .addArgument(new ClassExpr().setType(putClassQualifiedName))
                            .addArgument(nameExpr)
                            .addArgument(supplierExpression)
                            .setScope(new NameExpr("BeanContext"))
            );
        } else if (priorityExpr != null) {
            staticInitializer.addStatement(
                    new MethodCallExpr()
                            .setName("put")
                            .addArgument(new ClassExpr().setType(putClassQualifiedName))
                            .addArgument(priorityExpr)
                            .addArgument(supplierExpression)
                            .setScope(new NameExpr("BeanContext"))
            );
        } else if (isDefault) {
            staticInitializer.addStatement(
                    new MethodCallExpr()
                            .setName("put")
                            .addArgument(new ClassExpr().setType(putClassQualifiedName))
                            .addArgument(new BooleanLiteralExpr(true))
                            .addArgument(supplierExpression)
                            .setScope(new NameExpr("BeanContext"))
            );
        } else {
            staticInitializer.addStatement(
                    new MethodCallExpr()
                            .setName("put")
                            .addArgument(new ClassExpr().setType(putClassQualifiedName))
                            .addArgument(supplierExpression)
                            .setScope(new NameExpr("BeanContext"))
            );
        }
    }
}
