package io.nozdormu.config.processor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.UnknownType;
import com.google.auto.service.AutoService;
import io.nozdormu.common.ProcessorManager;
import io.nozdormu.spi.context.BeanContext;
import io.nozdormu.spi.context.BeanSupplier;
import io.nozdormu.spi.context.BeanSuppliers;
import io.nozdormu.spi.error.InjectionProcessException;
import jakarta.annotation.Generated;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static io.nozdormu.spi.error.InjectionProcessErrorType.CONFIG_PROPERTIES_PREFIX_NOT_EXIST;

@SupportedAnnotationTypes({
        "org.eclipse.microprofile.config.inject.ConfigProperties"
})
@AutoService(Processor.class)
public class ConfigProcessor extends AbstractProcessor {

    private static final Logger logger = LoggerFactory.getLogger(ConfigProcessor.class);

    private ProcessorManager processorManager;
    private final Map<TypeElement, CompilationUnit> compilationUnitListMap = new ConcurrentHashMap<>();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.processorManager = new ProcessorManager(processingEnv, ConfigProcessor.class.getClassLoader());
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            String rootPackageName = processorManager.getRootPackageName();
            CompilationUnit proxySuppliersCompilationUnit = buildProxySuppliers(rootPackageName, compilationUnitListMap);
            processorManager.writeToFiler(proxySuppliersCompilationUnit);
            processorManager.registerSpi("io.nozdormu.spi.context.BeanSuppliers", rootPackageName + ".ConfigSuppliers");
            processorManager.flushSpiFiles();
        }
        if (annotations.isEmpty()) {
            return false;
        }
        Set<? extends Element> ConfigPropertiesSet = roundEnv.getElementsAnnotatedWith(ConfigProperties.class);

        List<TypeElement> typeElements = ConfigPropertiesSet.stream()
                .filter(element -> element.getKind().isClass())
                .map(element -> (TypeElement) element)
                .collect(Collectors.toList());

        if (typeElements.isEmpty()) {
            return false;
        }

        processorManager.setRoundEnv(roundEnv);

        typeElements.forEach(typeElement ->
                processorManager.getCompilationUnit(typeElement).ifPresent(componentCompilationUnit ->
                        compilationUnitListMap.put(typeElement, componentCompilationUnit)
                )
        );
        return false;
    }

    private CompilationUnit buildProxySuppliers(String rootPackageName, Map<TypeElement, CompilationUnit> compilationUnitListMap) {
        logger.info("{} config suppliers class build start", rootPackageName);

        ClassOrInterfaceDeclaration suppliersClassDeclaration = new ClassOrInterfaceDeclaration()
                .addModifier(Modifier.Keyword.PUBLIC)
                .setName("ConfigSuppliers")
                .addAnnotation(new NormalAnnotationExpr().addPair("value", new StringLiteralExpr(getClass().getName())).setName(Generated.class.getSimpleName()))
                .addImplementedType(BeanSuppliers.class);

        CompilationUnit suppliersCompilationUnit = new CompilationUnit()
                .setPackageDeclaration(rootPackageName)
                .addType(suppliersClassDeclaration)
                .addImport(Generated.class)
                .addImport(BeanSuppliers.class)
                .addImport(BeanContext.class)
                .addImport(BeanSupplier.class)
                .addImport(Config.class)
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

        compilationUnitListMap.forEach((typeElement, configCompilationUnit) ->
                processorManager.getPublicClassOrInterfaceDeclaration(configCompilationUnit).ifPresent(configClassDeclaration -> {
                    String qualifiedName = processorManager.getQualifiedName(configClassDeclaration);
                    String configPrefix = qualifiedName.replaceAll("\\.", "_");
                    StringLiteralExpr propertyName = processorManager.getExplicitAnnotationValueAsString(
                                    typeElement,
                                    ConfigProperties.class.getName(),
                                    "prefix"
                            )
                            .map(StringLiteralExpr::new)
                            .orElseThrow(() -> new InjectionProcessException(CONFIG_PROPERTIES_PREFIX_NOT_EXIST.bind(qualifiedName)));

                    ClassOrInterfaceDeclaration holderClassOrInterfaceDeclaration = new ClassOrInterfaceDeclaration()
                            .setName(configPrefix + "_Holder")
                            .setModifiers(Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC);

                    holderClassOrInterfaceDeclaration
                            .addFieldWithInitializer(
                                    qualifiedName,
                                    "INSTANCE",
                                    getConfigMethodCall(
                                            qualifiedName,
                                            propertyName
                                    )
                            )
                            .setModifiers(Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC, Modifier.Keyword.FINAL);
                    suppliersClassDeclaration.addMember(holderClassOrInterfaceDeclaration);

                    Expression getInstanceExpression = new FieldAccessExpr()
                            .setName("INSTANCE")
                            .setScope(new NameExpr(configPrefix + "_Holder"));

                    staticInitializer.addStatement(
                            new VariableDeclarationExpr()
                                    .addVariable(
                                            new VariableDeclarator().setName(configPrefix + "_beanSupplier")
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

                    staticInitializer.addStatement(
                            new MethodCallExpr().setName("setQualifiers")
                                    .addArgument(mapOf)
                                    .setScope(new NameExpr(configPrefix + "_beanSupplier"))
                    );

                    staticInitializer.addStatement(
                            new MethodCallExpr().setName("setSupplier")
                                    .addArgument(
                                            new LambdaExpr()
                                                    .setEnclosingParameters(true)
                                                    .setBody(new ExpressionStmt(getInstanceExpression))
                                    )
                                    .setScope(new NameExpr(configPrefix + "_beanSupplier"))
                    );

                    staticInitializer.addStatement(
                            new MethodCallExpr().setName("put")
                                    .addArgument(new StringLiteralExpr(qualifiedName))
                                    .addArgument(new NameExpr(configPrefix + "_beanSupplier"))
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

        logger.info("{} config suppliers class build success", rootPackageName);
        return suppliersCompilationUnit;
    }

    private MethodCallExpr getConfigMethodCall(String qualifiedName, StringLiteralExpr propertyName) {
        return new MethodCallExpr()
                .setName("orElseGet")
                .addArgument(
                        new LambdaExpr()
                                .setEnclosingParameters(true)
                                .setBody(
                                        new ExpressionStmt(
                                                new ObjectCreationExpr().setType(qualifiedName)
                                        )
                                )
                )
                .setScope(
                        new MethodCallExpr()
                                .setName("getOptionalValue")
                                .addArgument(propertyName)
                                .addArgument(new ClassExpr().setType(qualifiedName))
                                .setScope(
                                        new MethodCallExpr()
                                                .setName("get")
                                                .addArgument(new ClassExpr().setType(Config.class))
                                                .setScope(new NameExpr().setName("BeanContext"))
                                )
                );
    }
}
