package io.nozdormu.inject.processor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.google.auto.service.AutoService;
import com.google.common.collect.Streams;
import io.nozdormu.common.ProcessorManager;
import io.nozdormu.spi.error.InjectionProcessException;
import io.nozdormu.spi.context.BaseModuleContext;
import io.nozdormu.spi.context.BeanContext;
import io.nozdormu.spi.context.ModuleContext;
import io.nozdormu.spi.context.ScopeInstanceFactory;
import jakarta.annotation.Generated;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.inject.Default;
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

        CompilationUnit moduleContextCompilationUnit = buildModuleContext(typeElements.stream().flatMap(typeElement -> processorManager.parse(typeElement).stream()).collect(Collectors.toList()), componentProxyCompilationUnits);
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
        return processorManager.parse(typeElement).map(this::buildComponentProxy).orElseThrow(() -> new InjectionProcessException(CANNOT_GET_COMPILATION_UNIT.bind(typeElement.getQualifiedName().toString())));
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

        CompilationUnit proxyCompilationUnit = new CompilationUnit()
                .addType(proxyClassDeclaration)
                .addImport(Generated.class);

//        componentClassDeclaration.getAnnotationByClass(Named.class)
//                .map(AnnotationExpr::clone)
//                .ifPresent(annotationExpr -> {
//                            annotationExpr.setParentNode(proxyClassDeclaration);
//                            proxyClassDeclaration.addAnnotation(annotationExpr);
//                            proxyCompilationUnit.addImport(Named.class);
//                        }
//                );
//
//        componentClassDeclaration.getAnnotationByClass(Default.class)
//                .map(AnnotationExpr::clone)
//                .ifPresent(annotationExpr -> {
//                            annotationExpr.setParentNode(proxyClassDeclaration);
//                            proxyClassDeclaration.addAnnotation(annotationExpr);
//                            proxyCompilationUnit.addImport(Default.class);
//                        }
//                );

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

        componentCompilationUnit.getPackageDeclaration()
                .map(PackageDeclaration::clone)
                .ifPresent(proxyCompilationUnit::setPackageDeclaration);

        processorManager.importAllClassOrInterfaceType(proxyClassDeclaration, componentClassDeclaration);

        componentProxyProcessors
                .forEach(componentProxyProcessor -> {
                            Logger.debug("processComponentProxy {}", componentProxyProcessor.getClass().getName());
                            componentProxyProcessor.processComponentProxy(componentCompilationUnit, componentClassDeclaration, proxyCompilationUnit, proxyClassDeclaration);
                        }
                );
        Logger.info("{} proxy class build success", componentClassDeclaration.getNameAsString());
        return proxyCompilationUnit;
    }

    private CompilationUnit buildModuleContext(List<CompilationUnit> componentCompilationUnits, List<CompilationUnit> componentProxyCompilationUnits) {
        ClassOrInterfaceDeclaration contextClassDeclaration = new ClassOrInterfaceDeclaration()
                .setPublic(true)
                .setName(processorManager.getRootPackageName().replaceAll("\\.", "_") + "_Context")
                .addAnnotation(
                        new SingleMemberAnnotationExpr()
                                .setMemberValue(new ClassExpr().setType(ModuleContext.class))
                                .setName(AutoService.class.getSimpleName())
                )
                .addAnnotation(
                        new NormalAnnotationExpr()
                                .addPair("value", new StringLiteralExpr(getClass().getName()))
                                .setName(Generated.class.getSimpleName())
                )
                .addExtendedType(BaseModuleContext.class);

        CompilationUnit contextCompilationUnit = new CompilationUnit()
                .addType(contextClassDeclaration)
                .addImport(AutoService.class)
                .addImport(Generated.class)
                .addImport(ModuleContext.class)
                .addImport(BaseModuleContext.class)
                .addImport(BeanContext.class);

        BlockStmt staticInitializer = contextClassDeclaration.addStaticInitializer();

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
                                                                                .orElseThrow(() -> new InjectionProcessException(CONSTRUCTOR_NOT_EXIST.bind(classOrInterfaceDeclaration.getNameAsString())))
                                                                )
                                                );

                                        holderClassDeclaration.addFieldWithInitializer(
                                                        qualifiedName,
                                                        "INSTANCE",
                                                        objectCreateExpression
                                                )
                                                .setModifiers(Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC, Modifier.Keyword.FINAL);

                                        contextClassDeclaration.addMember(holderClassDeclaration);
                                        addPutTypeStatement(staticInitializer, qualifiedName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, null, true);

                                        Optional<StringLiteralExpr> nameStringExpr = classOrInterfaceDeclaration.getAnnotationByClass(Named.class)
                                                .flatMap(processorManager::findAnnotationValue)
                                                .map(Expression::asStringLiteralExpr);

                                        nameStringExpr.ifPresent(stringLiteralExpr -> addPutTypeStatement(staticInitializer, qualifiedName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, stringLiteralExpr, true));

                                        Optional<StringLiteralExpr> defaultStringExpr = classOrInterfaceDeclaration.getAnnotationByClass(Default.class)
                                                .map(annotationExpr -> new StringLiteralExpr("default"));

                                        defaultStringExpr.ifPresent(stringLiteralExpr -> addPutTypeStatement(staticInitializer, qualifiedName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, stringLiteralExpr, true));

                                        processorManager.getExtendedTypes(classOrInterfaceDeclaration)
                                                .forEach(extendedTypeName -> {
                                                            addPutTypeStatement(staticInitializer, extendedTypeName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, null, true);
                                                            addPutTypeStatement(staticInitializer, extendedTypeName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, new StringLiteralExpr(qualifiedName), true);
                                                            nameStringExpr.ifPresent(stringLiteralExpr -> addPutTypeStatement(staticInitializer, extendedTypeName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, stringLiteralExpr, true));
                                                            defaultStringExpr.ifPresent(stringLiteralExpr -> addPutTypeStatement(staticInitializer, extendedTypeName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, stringLiteralExpr, true));
                                                        }
                                                );

                                        processorManager.getImplementedTypes(classOrInterfaceDeclaration)
                                                .forEach(implementedTypeName -> {
                                                            addPutTypeStatement(staticInitializer, implementedTypeName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, null, true);
                                                            addPutTypeStatement(staticInitializer, implementedTypeName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, new StringLiteralExpr(qualifiedName), true);
                                                            nameStringExpr.ifPresent(stringLiteralExpr -> addPutTypeStatement(staticInitializer, implementedTypeName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, stringLiteralExpr, true));
                                                            defaultStringExpr.ifPresent(stringLiteralExpr -> addPutTypeStatement(staticInitializer, implementedTypeName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, stringLiteralExpr, true));
                                                        }
                                                );
                                    } else {
                                        addPutTypeStatement(staticInitializer, qualifiedName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, null, false);

                                        Optional<StringLiteralExpr> nameStringExpr = classOrInterfaceDeclaration.getAnnotationByClass(Named.class)
                                                .flatMap(processorManager::findAnnotationValue)
                                                .map(Expression::asStringLiteralExpr);

                                        nameStringExpr.ifPresent(stringLiteralExpr -> addPutTypeStatement(staticInitializer, qualifiedName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, stringLiteralExpr, false));

                                        Optional<StringLiteralExpr> defaultStringExpr = classOrInterfaceDeclaration.getAnnotationByClass(Default.class)
                                                .map(annotationExpr -> new StringLiteralExpr("default"));

                                        defaultStringExpr.ifPresent(stringLiteralExpr -> addPutTypeStatement(staticInitializer, qualifiedName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, stringLiteralExpr, false));

                                        processorManager.getExtendedTypes(classOrInterfaceDeclaration)
                                                .forEach(extendedTypeName -> {
                                                            addPutTypeStatement(staticInitializer, extendedTypeName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, null, false);
                                                            addPutTypeStatement(staticInitializer, extendedTypeName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, new StringLiteralExpr(qualifiedName), false);
                                                            nameStringExpr.ifPresent(stringLiteralExpr -> addPutTypeStatement(staticInitializer, extendedTypeName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, stringLiteralExpr, false));
                                                            defaultStringExpr.ifPresent(stringLiteralExpr -> addPutTypeStatement(staticInitializer, extendedTypeName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, stringLiteralExpr, false));
                                                        }
                                                );

                                        processorManager.getImplementedTypes(classOrInterfaceDeclaration)
                                                .forEach(implementedTypeName -> {
                                                            addPutTypeStatement(staticInitializer, implementedTypeName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, null, false);
                                                            addPutTypeStatement(staticInitializer, implementedTypeName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, new StringLiteralExpr(qualifiedName), false);
                                                            nameStringExpr.ifPresent(stringLiteralExpr -> addPutTypeStatement(staticInitializer, implementedTypeName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, stringLiteralExpr, false));
                                                            defaultStringExpr.ifPresent(stringLiteralExpr -> addPutTypeStatement(staticInitializer, implementedTypeName, contextCompilationUnit, classOrInterfaceDeclaration, proxyClassOrInterfaceDeclaration, stringLiteralExpr, false));
                                                        }
                                                );
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

    private void addPutTypeStatement(BlockStmt staticInitializer, String putClassQualifiedName, CompilationUnit contextCompilationUnit, ClassOrInterfaceDeclaration classOrInterfaceDeclaration, ClassOrInterfaceDeclaration proxyClassOrInterfaceDeclaration, StringLiteralExpr nameStringExpr, boolean isSingleton) {
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
                                                    .orElseThrow(() -> new InjectionProcessException(CONSTRUCTOR_NOT_EXIST.bind(classOrInterfaceDeclaration.getNameAsString())))
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
        if (nameStringExpr != null) {
            staticInitializer.addStatement(
                    new MethodCallExpr()
                            .setName("put")
                            .addArgument(new ClassExpr().setType(putClassQualifiedName))
                            .addArgument(nameStringExpr)
                            .addArgument(supplierExpression)
            );
        } else {
            staticInitializer.addStatement(
                    new MethodCallExpr()
                            .setName("put")
                            .addArgument(new ClassExpr().setType(putClassQualifiedName))
                            .addArgument(supplierExpression)
            );
        }
    }

    private MethodCallExpr getBeanGetMethodCallExpr(NodeWithAnnotations<?> nodeWithAnnotations, CompilationUnit compilationUnit, ClassOrInterfaceType classOrInterfaceType) {
        Optional<StringLiteralExpr> nameStringExpr = nodeWithAnnotations.getAnnotationByClass(Default.class)
                .map(annotationExpr -> new StringLiteralExpr("default"))
                .or(() ->
                        nodeWithAnnotations.getAnnotationByClass(Named.class)
                                .flatMap(processorManager::findAnnotationValue)
                                .map(Expression::asStringLiteralExpr)
                );

        MethodCallExpr methodCallExpr;
        if (processorManager.getQualifiedName(classOrInterfaceType).equals(Provider.class.getName())) {
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
        } else {
            if (processorManager.getQualifiedName(classOrInterfaceType).equals(Mono.class.getName())) {
                methodCallExpr = new MethodCallExpr()
                        .setName("getMono")
                        .setScope(new NameExpr().setName("BeanContext"))
                        .addArgument(new ClassExpr().setType(processorManager.getQualifiedName(classOrInterfaceType.getTypeArguments().orElseThrow(() -> new InjectionProcessException(INSTANCE_TYPE_NOT_EXIST)).get(0))));
            } else {
                methodCallExpr = new MethodCallExpr()
                        .setName("get")
                        .setScope(new NameExpr().setName("BeanContext"))
                        .addArgument(new ClassExpr().setType(processorManager.getQualifiedName(classOrInterfaceType)));
            }
        }
        nameStringExpr.ifPresent(methodCallExpr::addArgument);
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
                                                    .setMemberValue(new ClassExpr().setType(ModuleContext.class))
                                                    .setName(AutoService.class.getSimpleName())
                                    )
                                    .addAnnotation(
                                            new NormalAnnotationExpr()
                                                    .addPair("value", new StringLiteralExpr(getClass().getName()))
                                                    .setName(Generated.class.getSimpleName())
                                    )
                                    .addExtendedType(BaseModuleContext.class);

                            CompilationUnit moduleContextCompilationUnit = new CompilationUnit()
                                    .addType(moduleContextClassDeclaration)
                                    .addImport(AutoService.class)
                                    .addImport(Generated.class)
                                    .addImport(ModuleContext.class)
                                    .addImport(BaseModuleContext.class)
                                    .addImport(BeanContext.class);

                            processorManager.importAllClassOrInterfaceType(moduleContextClassDeclaration, classOrInterfaceDeclaration);

                            compilationUnit.getPackageDeclaration()
                                    .ifPresent(packageDeclaration -> moduleContextCompilationUnit.setPackageDeclaration(packageDeclaration.getNameAsString()));

                            BlockStmt staticInitializer = moduleContextClassDeclaration.addStaticInitializer();

                            classOrInterfaceDeclaration.getMethods().stream()
                                    .filter(methodDeclaration -> methodDeclaration.isAnnotationPresent(Produces.class))
                                    .forEach(producesMethodDeclaration -> {
                                                String qualifiedName = processorManager.getQualifiedName(producesMethodDeclaration);

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

                                                    addPutTypeProducerStatement(staticInitializer, qualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, null, true);

                                                    processorManager.getMethodReturnResolvedReferenceType(producesMethodDeclaration)
                                                            .filter(resolvedReferenceType -> !resolvedReferenceType.getQualifiedName().equals(processorManager.getQualifiedName(producesMethodDeclaration.getType())))
                                                            .forEach(resolvedReferenceType ->
                                                                    addPutTypeProducerStatement(staticInitializer, qualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, null, true)
                                                            );

                                                    Optional<StringLiteralExpr> nameStringExpr = producesMethodDeclaration.getAnnotationByClass(Named.class)
                                                            .flatMap(processorManager::findAnnotationValue)
                                                            .map(Expression::asStringLiteralExpr);

                                                    nameStringExpr.ifPresent(stringLiteralExpr -> addPutTypeProducerStatement(staticInitializer, qualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, stringLiteralExpr, true));

                                                    Optional<StringLiteralExpr> defaultStringExpr = producesMethodDeclaration.getAnnotationByClass(Default.class)
                                                            .map(annotationExpr -> new StringLiteralExpr("default"));

                                                    defaultStringExpr.ifPresent(stringLiteralExpr -> addPutTypeProducerStatement(staticInitializer, qualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, stringLiteralExpr, true));

                                                    processorManager.getCompilationUnit(qualifiedName)
                                                            .map(returnTypeCompilationUnit -> processorManager.getPublicClassOrInterfaceDeclarationOrError(returnTypeCompilationUnit))
                                                            .ifPresent(returnTypeClassOrInterfaceDeclaration -> {
                                                                        processorManager.getExtendedTypes(returnTypeClassOrInterfaceDeclaration)
                                                                                .forEach(extendedTypeName -> {
                                                                                            addPutTypeProducerStatement(staticInitializer, extendedTypeName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, null, true);
                                                                                            addPutTypeProducerStatement(staticInitializer, extendedTypeName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, new StringLiteralExpr(qualifiedName), true);
                                                                                            nameStringExpr.ifPresent(stringLiteralExpr -> addPutTypeProducerStatement(staticInitializer, extendedTypeName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, stringLiteralExpr, true));
                                                                                            defaultStringExpr.ifPresent(stringLiteralExpr -> addPutTypeProducerStatement(staticInitializer, extendedTypeName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, stringLiteralExpr, true));
                                                                                        }
                                                                                );

                                                                        processorManager.getImplementedTypes(returnTypeClassOrInterfaceDeclaration)
                                                                                .forEach(implementedTypeName -> {
                                                                                            addPutTypeProducerStatement(staticInitializer, implementedTypeName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, null, true);
                                                                                            addPutTypeProducerStatement(staticInitializer, implementedTypeName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, new StringLiteralExpr(qualifiedName), true);
                                                                                            nameStringExpr.ifPresent(stringLiteralExpr -> addPutTypeProducerStatement(staticInitializer, implementedTypeName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, stringLiteralExpr, true));
                                                                                            defaultStringExpr.ifPresent(stringLiteralExpr -> addPutTypeProducerStatement(staticInitializer, implementedTypeName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, stringLiteralExpr, true));
                                                                                        }
                                                                                );

                                                                    }
                                                            );
                                                } else {
                                                    addPutTypeProducerStatement(staticInitializer, qualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, null, false);
                                                    Optional<StringLiteralExpr> nameStringExpr = producesMethodDeclaration.getAnnotationByClass(Named.class)
                                                            .flatMap(processorManager::findAnnotationValue)
                                                            .map(Expression::asStringLiteralExpr);
                                                    nameStringExpr.ifPresent(stringLiteralExpr -> addPutTypeProducerStatement(staticInitializer, qualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, stringLiteralExpr, false));

                                                    Optional<StringLiteralExpr> defaultStringExpr = producesMethodDeclaration.getAnnotationByClass(Default.class)
                                                            .map(annotationExpr -> new StringLiteralExpr("default"));
                                                    defaultStringExpr.ifPresent(stringLiteralExpr -> addPutTypeProducerStatement(staticInitializer, qualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, stringLiteralExpr, false));

                                                    processorManager.getCompilationUnit(qualifiedName)
                                                            .map(returnTypeCompilationUnit -> processorManager.getPublicClassOrInterfaceDeclarationOrError(returnTypeCompilationUnit))
                                                            .ifPresent(returnTypeClassOrInterfaceDeclaration -> {
                                                                        processorManager.getExtendedTypes(returnTypeClassOrInterfaceDeclaration)
                                                                                .forEach(extendedTypeName -> {
                                                                                            addPutTypeProducerStatement(staticInitializer, extendedTypeName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, null, false);
                                                                                            addPutTypeProducerStatement(staticInitializer, extendedTypeName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, new StringLiteralExpr(qualifiedName), false);
                                                                                            nameStringExpr.ifPresent(stringLiteralExpr -> addPutTypeProducerStatement(staticInitializer, extendedTypeName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, stringLiteralExpr, false));
                                                                                            defaultStringExpr.ifPresent(stringLiteralExpr -> addPutTypeProducerStatement(staticInitializer, extendedTypeName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, stringLiteralExpr, false));
                                                                                        }
                                                                                );

                                                                        processorManager.getImplementedTypes(returnTypeClassOrInterfaceDeclaration)
                                                                                .forEach(implementedTypeName -> {
                                                                                            addPutTypeProducerStatement(staticInitializer, implementedTypeName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, null, false);
                                                                                            addPutTypeProducerStatement(staticInitializer, implementedTypeName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, new StringLiteralExpr(qualifiedName), false);
                                                                                            nameStringExpr.ifPresent(stringLiteralExpr -> addPutTypeProducerStatement(staticInitializer, implementedTypeName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, stringLiteralExpr, false));
                                                                                            defaultStringExpr.ifPresent(stringLiteralExpr -> addPutTypeProducerStatement(staticInitializer, implementedTypeName, moduleContextCompilationUnit, classOrInterfaceDeclaration, producesMethodDeclaration, stringLiteralExpr, false));
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

    private void addPutTypeProducerStatement(BlockStmt staticInitializer, String putClassQualifiedName, CompilationUnit moduleContextCompilationUnit, ClassOrInterfaceDeclaration classOrInterfaceDeclaration, MethodDeclaration methodDeclaration, StringLiteralExpr nameStringExpr, boolean isSingleton) {
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
        if (nameStringExpr != null) {
            staticInitializer.addStatement(
                    new MethodCallExpr()
                            .setName("put")
                            .addArgument(new ClassExpr().setType(putClassQualifiedName))
                            .addArgument(nameStringExpr)
                            .addArgument(supplierExpression)
            );
        } else {
            staticInitializer.addStatement(
                    new MethodCallExpr()
                            .setName("put")
                            .addArgument(new ClassExpr().setType(putClassQualifiedName))
                            .addArgument(supplierExpression)
            );
        }
    }
}
