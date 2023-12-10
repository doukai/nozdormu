package io.nozdormu.inject.processor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.google.auto.service.AutoService;
import com.google.common.collect.Streams;
import io.nozdormu.inject.ProcessorManager;
import io.nozdormu.inject.error.InjectionProcessException;
import io.nozdormu.spi.context.BaseModuleContext;
import io.nozdormu.spi.context.ModuleContext;
import jakarta.annotation.Generated;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.inject.Default;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.transaction.TransactionScoped;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.tinylog.Logger;
import reactor.core.publisher.Mono;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.nozdormu.inject.error.InjectionProcessErrorType.*;
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

    private Set<ComponentProxyProcessor> componentProxyProcessors;
    private ProcessorManager processorManager;

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
                .filter(element -> element.getKind().isClass())
                .map(element -> (TypeElement) element)
                .collect(Collectors.toList());

        if (typeElements.size() == 0) {
            return false;
        }

        processorManager.setRoundEnv(roundEnv);
        componentProxyProcessors
                .forEach(componentProxyProcessor -> {
                            Logger.debug("inProcess {}", componentProxyProcessor.getClass().getName());
                            componentProxyProcessor.inProcess();
                        }
                );

        List<CompilationUnit> componentProxyCompilationUnits = typeElements.stream()
                .filter(typeElement -> typeElement.getAnnotation(ConfigProperties.class) == null)
                .map(this::buildComponentProxy)
                .collect(Collectors.toList());
        componentProxyCompilationUnits.forEach(compilationUnit -> processorManager.writeToFiler(compilationUnit));
        Logger.debug("all proxy class build success");

        CompilationUnit moduleContextCompilationUnit = buildModuleContext(typeElementStream.flatMap(typeElement -> processorManager.parse(typeElement).stream()).collect(Collectors.toList()));
        processorManager.writeToFiler(moduleContextCompilationUnit);
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
                .setName(componentClassDeclaration.getNameAsString() + "Proxy")
                .addAnnotation(new NormalAnnotationExpr().addPair("value", new StringLiteralExpr(getClass().getName())).setName(Generated.class.getSimpleName()));

        componentClassDeclaration.getConstructors().stream()
                .map(ConstructorDeclaration::clone)
                .forEach(constructorDeclaration -> {
                            constructorDeclaration.setParentNode(proxyClassDeclaration);
                            proxyClassDeclaration
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
                        }
                );

        CompilationUnit proxyCompilationUnit = new CompilationUnit()
                .addType(proxyClassDeclaration)
                .addImport(Generated.class);

        componentClassDeclaration.getAnnotationByClass(Named.class)
                .map(AnnotationExpr::clone)
                .ifPresent(annotationExpr -> {
                            annotationExpr.setParentNode(proxyClassDeclaration);
                            proxyClassDeclaration.addAnnotation(annotationExpr);
                            proxyCompilationUnit.addImport(Named.class);
                        }
                );

        componentClassDeclaration.getAnnotationByClass(Default.class)
                .map(AnnotationExpr::clone)
                .ifPresent(annotationExpr -> {
                            annotationExpr.setParentNode(proxyClassDeclaration);
                            proxyClassDeclaration.addAnnotation(annotationExpr);
                            proxyCompilationUnit.addImport(Default.class);
                        }
                );

        componentCompilationUnit.getPackageDeclaration()
                .ifPresent(packageDeclaration -> proxyCompilationUnit.setPackageDeclaration(packageDeclaration.getNameAsString()));

        processorManager.importAllClassOrInterfaceType(proxyClassDeclaration, componentClassDeclaration);

        componentProxyProcessors
                .forEach(componentProxyProcessor -> {
                            Logger.debug("processComponentProxy {}", componentProxyProcessor.getClass().getName());
                            componentProxyProcessor.processComponentProxy(componentCompilationUnit, componentClassDeclaration, proxyCompilationUnit, proxyClassDeclaration);
                        }
                );
        Logger.info("{} proxy class build success", processorManager.getQualifiedNameByDeclaration(componentClassDeclaration));
        return proxyCompilationUnit;
    }

    private CompilationUnit buildModuleContext(List<CompilationUnit> compilationUnitList) {

        ClassOrInterfaceDeclaration moduleContextInterfaceDeclaration = new ClassOrInterfaceDeclaration()
                .setPublic(true)
                .setName(processorManager.getRootPackageName().replaceAll("\\.", "_") + "_Context")
                .addAnnotation(
                        new SingleMemberAnnotationExpr()
                                .setMemberValue(new ClassExpr().setType(ModuleContext.class))
                                .setName(AutoService.class.getSimpleName())
                )
                .addExtendedType(BaseModuleContext.class);

        CompilationUnit moduleContextCompilationUnit = new CompilationUnit()
                .addType(moduleContextInterfaceDeclaration)
                .addImport(AutoService.class)
                .addImport(ModuleContext.class)
                .addImport(BaseModuleContext.class);

        BlockStmt staticInitializer = moduleContextInterfaceDeclaration.addStaticInitializer();

        compilationUnitList.forEach(compilationUnit -> {
                    processorManager.getPublicClassOrInterfaceDeclaration(compilationUnit)
                            .ifPresent(classOrInterfaceDeclaration -> {
                                        String qualifiedName = processorManager.getQualifiedName(classOrInterfaceDeclaration);
                                        Optional<String> packageName = compilationUnit.getPackageDeclaration().map(NodeWithName::getNameAsString);
                                        String variablePrefix = packageName.map(name -> name.replaceAll("\\.", "_")).map(name -> name + "_").orElse("");
                                        if (classOrInterfaceDeclaration.isAnnotationPresent(Singleton.class) || classOrInterfaceDeclaration.isAnnotationPresent(ApplicationScoped.class)) {
                                            ClassOrInterfaceDeclaration holderClassOrInterfaceDeclaration = new ClassOrInterfaceDeclaration();
                                            holderClassOrInterfaceDeclaration.setName(variablePrefix + classOrInterfaceDeclaration.getNameAsString() + "Holder");
                                            holderClassOrInterfaceDeclaration.setModifier()
                                            ClassOrInterfaceDeclaration holderClass = classOrInterfaceDeclaration.addMember(new ClassOrInterfaceDeclaration().setName(variablePrefix + classOrInterfaceDeclaration.getNameAsString() + "Holder", Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC));
                                            holderClass.addFieldWithInitializer(
                                                    qualifiedName,
                                                    "INSTANCE",
                                                    new ObjectCreationExpr()
                                                            .setType(qualifiedName)
                                                            .setArguments(
                                                                    classOrInterfaceDeclaration.getConstructors().stream()
                                                                            .findFirst()
                                                                            .map(constructorDeclaration ->
                                                                                    constructorDeclaration.getParameters().stream()
                                                                                            .map(parameter -> getBeanGetMethodCallExpr(parameter, moduleContextCompilationUnit, parameter.getType().asClassOrInterfaceType()))
                                                                                            .map(methodCallExpr -> (Expression) methodCallExpr)
                                                                                            .collect(Collectors.toCollection(NodeList::new))
                                                                            )
                                                                            .orElseThrow(() -> new InjectionProcessException(CONSTRUCTOR_NOT_EXIST.bind(classOrInterfaceDeclaration.getNameAsString())))
                                                            )
                                            );
                                            addPutTypeStatement(staticInitializer, qualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, null, true, variablePrefix);

                                            Optional<StringLiteralExpr> nameStringExpr = classOrInterfaceDeclaration.getAnnotationByClass(Named.class)
                                                    .flatMap(processorManager::findAnnotationValue)
                                                    .map(Expression::asStringLiteralExpr);

                                            nameStringExpr.ifPresent(stringLiteralExpr -> addPutTypeStatement(staticInitializer, qualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, stringLiteralExpr, true, variablePrefix));

                                            Optional<StringLiteralExpr> defaultStringExpr = classOrInterfaceDeclaration.getAnnotationByClass(Default.class)
                                                    .map(annotationExpr -> new StringLiteralExpr("default"));

                                            defaultStringExpr.ifPresent(stringLiteralExpr -> addPutTypeStatement(staticInitializer, qualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, stringLiteralExpr, true, variablePrefix));

                                            classOrInterfaceDeclaration.getExtendedTypes()
                                                    .forEach(extendedType -> {
                                                                String putClassQualifiedName = processorManager.getQualifiedName(extendedType);
                                                                addPutTypeStatement(staticInitializer, putClassQualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, null, true, variablePrefix);
                                                                nameStringExpr.ifPresent(stringLiteralExpr -> addPutTypeStatement(staticInitializer, putClassQualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, stringLiteralExpr, true, variablePrefix));
                                                                defaultStringExpr.ifPresent(stringLiteralExpr -> addPutTypeStatement(staticInitializer, putClassQualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, stringLiteralExpr, true, variablePrefix));
                                                            }
                                                    );

                                            classOrInterfaceDeclaration.getImplementedTypes()
                                                    .forEach(implementedType -> {
                                                                String putClassQualifiedName = processorManager.getQualifiedName(implementedType);
                                                                addPutTypeStatement(staticInitializer, putClassQualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, null, true, variablePrefix);
                                                                nameStringExpr.ifPresent(stringLiteralExpr -> addPutTypeStatement(staticInitializer, putClassQualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, stringLiteralExpr, true, variablePrefix));
                                                                defaultStringExpr.ifPresent(stringLiteralExpr -> addPutTypeStatement(staticInitializer, putClassQualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, stringLiteralExpr, true, variablePrefix));
                                                            }
                                                    );
                                        } else {
                                            addPutTypeStatement(staticInitializer, qualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, null, false, null);

                                            Optional<StringLiteralExpr> nameStringExpr = classOrInterfaceDeclaration.getAnnotationByClass(Named.class)
                                                    .flatMap(processorManager::findAnnotationValue)
                                                    .map(Expression::asStringLiteralExpr);

                                            nameStringExpr.ifPresent(stringLiteralExpr -> addPutTypeStatement(staticInitializer, qualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, stringLiteralExpr, false, null));

                                            Optional<StringLiteralExpr> defaultStringExpr = classOrInterfaceDeclaration.getAnnotationByClass(Default.class)
                                                    .map(annotationExpr -> new StringLiteralExpr("default"));

                                            defaultStringExpr.ifPresent(stringLiteralExpr -> addPutTypeStatement(staticInitializer, qualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, stringLiteralExpr, false, null));

                                            classOrInterfaceDeclaration.getExtendedTypes()
                                                    .forEach(extendedType -> {
                                                                String putClassQualifiedName = processorManager.getQualifiedName(extendedType);
                                                                addPutTypeStatement(staticInitializer, putClassQualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, null, false, null);
                                                                nameStringExpr.ifPresent(stringLiteralExpr -> addPutTypeStatement(staticInitializer, putClassQualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, stringLiteralExpr, false, null));
                                                                defaultStringExpr.ifPresent(stringLiteralExpr -> addPutTypeStatement(staticInitializer, putClassQualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, stringLiteralExpr, false, null));
                                                            }
                                                    );

                                            classOrInterfaceDeclaration.getImplementedTypes()
                                                    .forEach(implementedType -> {
                                                                String putClassQualifiedName = processorManager.getQualifiedName(implementedType);
                                                                addPutTypeStatement(staticInitializer, putClassQualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, null, false, null);
                                                                nameStringExpr.ifPresent(stringLiteralExpr -> addPutTypeStatement(staticInitializer, putClassQualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, stringLiteralExpr, false, null));
                                                                defaultStringExpr.ifPresent(stringLiteralExpr -> addPutTypeStatement(staticInitializer, putClassQualifiedName, moduleContextCompilationUnit, classOrInterfaceDeclaration, stringLiteralExpr, false, null));
                                                            }
                                                    );
                                        }
                                    }
                            );
                }
        );
        return moduleContextCompilationUnit;
    }

    private MethodCallExpr getBeanGetMethodCallExpr(NodeWithAnnotations<?> nodeWithAnnotations, CompilationUnit belongCompilationUnit, ClassOrInterfaceType classOrInterfaceType) {
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
            belongCompilationUnit.addImport(Provider.class);
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

    private void addPutTypeStatement(BlockStmt staticInitializer, String putClassQualifiedName, CompilationUnit moduleContextCompilationUnit, ClassOrInterfaceDeclaration classOrInterfaceDeclaration, StringLiteralExpr nameStringExpr, boolean isSingleton, String variablePrefix) {
        Expression supplierExpression;
        String instanceClassQualifiedName = processorManager.getQualifiedName(classOrInterfaceDeclaration);
        if (isSingleton) {
            supplierExpression = new LambdaExpr()
                    .setEnclosingParameters(true)
                    .setBody(
                            new ExpressionStmt()
                                    .setExpression(
                                            new FieldAccessExpr()
                                                    .setName("INSTANCE")
                                                    .setScope(new NameExpr(variablePrefix + classOrInterfaceDeclaration.getNameAsString() + "Holder"))
                                    )
                    );
        } else {
            supplierExpression = new LambdaExpr()
                    .setEnclosingParameters(true)
                    .setBody(
                            new ExpressionStmt()
                                    .setExpression(
                                            new ObjectCreationExpr()
                                                    .setType(instanceClassQualifiedName)
                                                    .setArguments(
                                                            classOrInterfaceDeclaration.getConstructors().stream()
                                                                    .findFirst()
                                                                    .map(constructorDeclaration ->
                                                                            constructorDeclaration.getParameters().stream()
                                                                                    .map(parameter -> getBeanGetMethodCallExpr(parameter, moduleContextCompilationUnit, parameter.getType().asClassOrInterfaceType()))
                                                                                    .map(methodCallExpr -> (Expression) methodCallExpr)
                                                                                    .collect(Collectors.toCollection(NodeList::new))
                                                                    )
                                                                    .orElseThrow(() -> new InjectionProcessException(CONSTRUCTOR_NOT_EXIST.bind(classOrInterfaceDeclaration.getNameAsString())))
                                                    )
                                    )
                    );
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
