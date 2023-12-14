package io.nozdormu.config.processor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.google.auto.service.AutoService;
import io.nozdormu.common.ProcessorManager;
import io.nozdormu.spi.context.BaseModuleContext;
import io.nozdormu.spi.context.BeanContext;
import io.nozdormu.spi.context.ModuleContext;
import io.nozdormu.spi.error.InjectionProcessException;
import jakarta.annotation.Generated;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.tinylog.Logger;

import javax.annotation.processing.*;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static io.nozdormu.spi.error.InjectionProcessErrorType.CONFIG_PROPERTIES_PREFIX_NOT_EXIST;

@SupportedAnnotationTypes({
        "org.eclipse.microprofile.config.inject.ConfigProperties"
})
@AutoService(Processor.class)
public class ConfigProcessor extends AbstractProcessor {

    private ProcessorManager processorManager;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.processorManager = new ProcessorManager(processingEnv, ConfigProcessor.class.getClassLoader());
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
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

        CompilationUnit moduleContextCompilationUnit = buildModuleContext(typeElements.stream().flatMap(typeElement -> processorManager.parse(typeElement).stream()).collect(Collectors.toList()));

        buildDefaultConfig(typeElements.stream().flatMap(typeElement -> processorManager.parse(typeElement).stream()).collect(Collectors.toList()))
                .forEach(s -> System.out.println(s));
        processorManager.writeToFiler(moduleContextCompilationUnit);
        Logger.debug("module context class build success");

        return false;
    }

    public CompilationUnit buildModuleContext(List<CompilationUnit> componentCompilationUnits) {
        ClassOrInterfaceDeclaration contextClassDeclaration = new ClassOrInterfaceDeclaration()
                .setPublic(true)
                .setName(processorManager.getRootPackageName().replaceAll("\\.", "_") + "_Config_Context")
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
                .addImport(BeanContext.class)
                .addImport(Config.class)
                .addImport(ConfigProvider.class);

        BlockStmt staticInitializer = contextClassDeclaration.addStaticInitializer();

        componentCompilationUnits
                .forEach(compilationUnit -> {
                            ClassOrInterfaceDeclaration configClassDeclaration = processorManager.getPublicClassOrInterfaceDeclarationOrError(compilationUnit);
                            String qualifiedName = processorManager.getQualifiedName(configClassDeclaration);

                            StringLiteralExpr propertyName = configClassDeclaration.getAnnotationByClass(ConfigProperties.class)
                                    .flatMap(annotationExpr ->
                                            annotationExpr.asNormalAnnotationExpr().getPairs().stream()
                                                    .filter(memberValuePair -> memberValuePair.getNameAsString().equals("prefix"))
                                                    .findFirst()
                                                    .map(memberValuePair -> memberValuePair.getValue().asStringLiteralExpr())
                                    )
                                    .orElseThrow(() -> new InjectionProcessException(CONFIG_PROPERTIES_PREFIX_NOT_EXIST.bind(qualifiedName)));

                            ClassOrInterfaceDeclaration holderClassOrInterfaceDeclaration = new ClassOrInterfaceDeclaration()
                                    .setName(qualifiedName.replaceAll("\\.", "_") + "Holder")
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
                            contextClassDeclaration.addMember(holderClassOrInterfaceDeclaration);

                            staticInitializer.addStatement(
                                    new MethodCallExpr()
                                            .setName("put")
                                            .addArgument(new ClassExpr().setType(qualifiedName))
                                            .addArgument(
                                                    new LambdaExpr()
                                                            .setEnclosingParameters(true)
                                                            .setBody(
                                                                    new ExpressionStmt()
                                                                            .setExpression(
                                                                                    new FieldAccessExpr()
                                                                                            .setName("INSTANCE")
                                                                                            .setScope(new NameExpr(qualifiedName.replaceAll("\\.", "_") + "Holder"))
                                                                            )
                                                            )
                                            )
                            );
                        }
                );
        Logger.info("{} module context class build success", contextClassDeclaration.getNameAsString());
        return contextCompilationUnit;
    }

    private MethodCallExpr getConfigMethodCall(String qualifiedName, StringLiteralExpr propertyName) {
        return new MethodCallExpr()
                .setName("getValue")
                .addArgument(propertyName)
                .addArgument(new ClassExpr().setType(qualifiedName))
                .setScope(
                        new MethodCallExpr()
                                .setName("get")
                                .addArgument(new ClassExpr().setType(Config.class))
                                .setScope(new NameExpr().setName("BeanContext"))
                );
    }

    public List<String> buildDefaultConfig(List<CompilationUnit> componentCompilationUnits) {
        return componentCompilationUnits.stream()
                .map(compilationUnit -> {
                            ClassOrInterfaceDeclaration configClassDeclaration = processorManager.getPublicClassOrInterfaceDeclarationOrError(compilationUnit);
                            String qualifiedName = processorManager.getQualifiedName(configClassDeclaration);
                            StringLiteralExpr propertyName = configClassDeclaration.getAnnotationByClass(ConfigProperties.class)
                                    .flatMap(annotationExpr ->
                                            annotationExpr.asNormalAnnotationExpr().getPairs().stream()
                                                    .filter(memberValuePair -> memberValuePair.getNameAsString().equals("prefix"))
                                                    .findFirst()
                                                    .map(memberValuePair -> memberValuePair.getValue().asStringLiteralExpr())
                                    )
                                    .orElseThrow(() -> new InjectionProcessException(CONFIG_PROPERTIES_PREFIX_NOT_EXIST.bind(qualifiedName)));

                            StringBuilder configBuilder = new StringBuilder();
                            configBuilder.append(propertyName.getValue()).append(" {\n");
                            configClassDeclaration.getFields().stream()
                                    .filter(FieldDeclaration::isFieldDeclaration)
                                    .map(FieldDeclaration::asFieldDeclaration)
                                    .forEach(fieldDeclaration ->
                                            fieldDeclaration.getVariables()
                                                    .forEach(variableDeclarator ->
                                                            variableDeclarator.getInitializer()
                                                                    .filter(item -> item.isLiteralExpr() || item.isArrayInitializerExpr())
                                                                    .ifPresent(expression ->
                                                                            configBuilder.append("  ").append(variableDeclarator.getNameAsString()).append(" = ").append(expressionToConfig(expression)).append("\n")
                                                                    )
                                                    )
                                    );
                            configBuilder.append("}");
                            return configBuilder.toString();
                        }
                )
                .collect(Collectors.toList());
    }

    private String expressionToConfig(Expression expression) {
        StringBuilder configBuilder = new StringBuilder();
        if (expression.isLiteralExpr()) {
            configBuilder.append(expression);
        } else if (expression.isArrayInitializerExpr()) {
            configBuilder
                    .append("[")
                    .append(
                            expression.asArrayInitializerExpr().getValues().stream()
                                    .filter(item -> item.isLiteralExpr() || item.isArrayInitializerExpr())
                                    .map(this::expressionToConfig)
                                    .collect(Collectors.joining(", "))
                    )
                    .append("]");
        }
        return configBuilder.toString();
    }
}
