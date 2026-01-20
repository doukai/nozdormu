package io.nozdormu.interceptor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.google.auto.service.AutoService;
import io.nozdormu.common.ProcessorManager;
import io.nozdormu.inject.processor.InjectProcessor;
import io.nozdormu.spi.context.BeanContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Named;
import jakarta.interceptor.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SupportedAnnotationTypes({
        "jakarta.interceptor.Interceptor"
})
@AutoService(Processor.class)
public class InterceptorProcessor extends AbstractProcessor {

    private static final Logger logger = LoggerFactory.getLogger(InterceptorProcessor.class);

    private ProcessorManager processorManager;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.processorManager = new ProcessorManager(processingEnv, InjectProcessor.class.getClassLoader());
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (annotations.isEmpty()) {
            return false;
        }
        List<TypeElement> interceptorList = roundEnv.getElementsAnnotatedWith(Interceptor.class).stream()
                .filter(element -> element.getKind().isClass())
                .map(element -> (TypeElement) element)
                .collect(Collectors.toList());

        interceptorList.stream()
                .filter(typeElement ->
                        typeElement.getEnclosedElements().stream()
                                .filter(element -> element.getKind().equals(ElementKind.METHOD))
                                .anyMatch(element -> element.getAnnotation(AroundInvoke.class) != null)
                )
                .flatMap(this::buildInvokeInterceptor)
                .forEach(compilationUnit -> processorManager.writeToFiler(compilationUnit));

        interceptorList.stream()
                .filter(typeElement ->
                        typeElement.getEnclosedElements().stream()
                                .filter(element -> element.getKind().equals(ElementKind.METHOD))
                                .anyMatch(element -> element.getAnnotation(AroundConstruct.class) != null)
                )
                .flatMap(this::buildConstructInterceptor)
                .forEach(compilationUnit -> processorManager.writeToFiler(compilationUnit));
        return false;
    }

    private Stream<CompilationUnit> buildInvokeInterceptor(TypeElement typeElement) {
        return processorManager.getCompilationUnit(typeElement).stream()
                .flatMap(compilationUnit -> {
                    ClassOrInterfaceDeclaration interceptorClassDeclaration = processorManager.getPublicClassOrInterfaceDeclarationOrError(compilationUnit);
                    return interceptorClassDeclaration.getAnnotations().stream()
                            .filter(annotationExpr -> {
                                CompilationUnit annotationCompilationUnit = processorManager.getCompilationUnitOrError(annotationExpr);
                                AnnotationDeclaration annotationDeclaration = processorManager.getPublicAnnotationDeclarationOrError(annotationCompilationUnit);
                                return annotationDeclaration.getAnnotations().stream()
                                        .anyMatch(subAnnotationExpr -> processorManager.getQualifiedName(subAnnotationExpr).equals(InterceptorBinding.class.getName()));
                            })
                            .flatMap(annotationExpr ->
                                    interceptorClassDeclaration.getMethods().stream()
                                            .filter(methodDeclaration -> methodDeclaration.isAnnotationPresent(AroundInvoke.class))
                                            .map(methodDeclaration -> {
                                                logger.info("{} proxy class build start", interceptorClassDeclaration.getFullyQualifiedName().orElseGet(interceptorClassDeclaration::getNameAsString));

                                                String name = interceptorClassDeclaration.getNameAsString() + annotationExpr.getNameAsString() + "_" + methodDeclaration.getNameAsString() + interceptorClassDeclaration.getMethods().indexOf(methodDeclaration) + "InvokeInterceptor";
                                                ClassOrInterfaceDeclaration invokeInterceptorDeclaration = new ClassOrInterfaceDeclaration()
                                                        .addModifier(Modifier.Keyword.PUBLIC)
                                                        .addImplementedType(InvokeInterceptor.class)
                                                        .setName(name)
                                                        .addAnnotation(Dependent.class)
                                                        .addAnnotation(new NormalAnnotationExpr().addPair("value", new StringLiteralExpr(processorManager.getQualifiedName(annotationExpr))).setName(Named.class.getSimpleName()));

                                                CompilationUnit invokeInterceptorCompilationUnit = new CompilationUnit()
                                                        .addType(invokeInterceptorDeclaration)
                                                        .addImport(InvokeInterceptor.class)
                                                        .addImport(BeanContext.class)
                                                        .addImport(InvocationContextProxy.class)
                                                        .addImport(processorManager.getQualifiedName(interceptorClassDeclaration))
                                                        .addImport(Dependent.class)
                                                        .addImport(Named.class);

                                                invokeInterceptorCompilationUnit.addImport(Priority.class);
                                                interceptorClassDeclaration.getAnnotationByClass(Priority.class)
                                                        .ifPresent(invokeInterceptorDeclaration::addAnnotation);

                                                compilationUnit.getPackageDeclaration()
                                                        .ifPresent(invokeInterceptorCompilationUnit::setPackageDeclaration);

                                                processorManager.importAllClassOrInterfaceType(invokeInterceptorDeclaration, interceptorClassDeclaration);

                                                FieldDeclaration invocationContext = new FieldDeclaration()
                                                        .setModifiers(Modifier.Keyword.PRIVATE)
                                                        .addVariable(
                                                                new VariableDeclarator()
                                                                        .setName("invocationContext")
                                                                        .setType(InvocationContext.class)
                                                                        .setInitializer(
                                                                                new MethodCallExpr()
                                                                                        .setName("setOwner")
                                                                                        .addArgument(new ClassExpr().setType(processorManager.getQualifiedName(annotationExpr)))
                                                                                        .setScope(new ObjectCreationExpr().setType(InvocationContextProxy.class))
                                                                        )
                                                        );
                                                invokeInterceptorDeclaration.addMember(invocationContext);

                                                MethodDeclaration getContext = new MethodDeclaration()
                                                        .setName("getContext")
                                                        .setModifiers(Modifier.Keyword.PUBLIC)
                                                        .setType(InvocationContext.class)
                                                        .setBody(
                                                                new BlockStmt()
                                                                        .addStatement(
                                                                                new ReturnStmt()
                                                                                        .setExpression(
                                                                                                new NameExpr("invocationContext")
                                                                                        )
                                                                        )
                                                        );
                                                invokeInterceptorDeclaration.addMember(getContext);

                                                MethodDeclaration aroundInvoke = new MethodDeclaration()
                                                        .setName("aroundInvoke")
                                                        .setModifiers(Modifier.Keyword.PUBLIC)
                                                        .setType(Object.class)
                                                        .addParameter(InvocationContext.class, "invocationContext")
                                                        .addAnnotation(Override.class)
                                                        .setBody(
                                                                new BlockStmt()
                                                                        .addStatement(
                                                                                new ReturnStmt()
                                                                                        .setExpression(
                                                                                                new MethodCallExpr()
                                                                                                        .setName(methodDeclaration.getNameAsString())
                                                                                                        .addArgument("invocationContext")
                                                                                                        .setScope(
                                                                                                                new MethodCallExpr()
                                                                                                                        .setName("get")
                                                                                                                        .setScope(
                                                                                                                                new MethodCallExpr("getProvider")
                                                                                                                                        .setScope(new NameExpr("BeanContext"))
                                                                                                                                        .addArgument(new ClassExpr().setType(interceptorClassDeclaration.getNameAsString()))
                                                                                                                        )
                                                                                                        )
                                                                                        )
                                                                        )
                                                        );
                                                invokeInterceptorDeclaration.addMember(aroundInvoke);

                                                logger.info("{} proxy class build success", interceptorClassDeclaration.getFullyQualifiedName().orElseGet(interceptorClassDeclaration::getNameAsString));
                                                return invokeInterceptorCompilationUnit;
                                            })
                            );
                });
    }

    private Stream<CompilationUnit> buildConstructInterceptor(TypeElement typeElement) {
        return processorManager.getCompilationUnit(typeElement).stream()
                .flatMap(compilationUnit -> {
                    ClassOrInterfaceDeclaration interceptorClassDeclaration = processorManager.getPublicClassOrInterfaceDeclarationOrError(compilationUnit);
                    return interceptorClassDeclaration.getAnnotations().stream()
                            .filter(annotationExpr -> {
                                CompilationUnit annotationCompilationUnit = processorManager.getCompilationUnitOrError(annotationExpr);
                                AnnotationDeclaration annotationDeclaration = processorManager.getPublicAnnotationDeclarationOrError(annotationCompilationUnit);
                                return annotationDeclaration.getAnnotations().stream()
                                        .anyMatch(subAnnotationExpr -> processorManager.getQualifiedName(subAnnotationExpr).equals(InterceptorBinding.class.getName()));
                            })
                            .flatMap(annotationExpr ->
                                    interceptorClassDeclaration.getMethods().stream()
                                            .filter(methodDeclaration -> methodDeclaration.isAnnotationPresent(AroundConstruct.class))
                                            .map(methodDeclaration -> {
                                                String name = interceptorClassDeclaration.getNameAsString() + annotationExpr.getNameAsString() + "_" + methodDeclaration.getNameAsString() + interceptorClassDeclaration.getMethods().indexOf(methodDeclaration) + "ConstructInterceptor";
                                                ClassOrInterfaceDeclaration constructInterceptorDeclaration = new ClassOrInterfaceDeclaration()
                                                        .addModifier(Modifier.Keyword.PUBLIC)
                                                        .addImplementedType(ConstructInterceptor.class)
                                                        .setName(name)
                                                        .addAnnotation(Dependent.class)
                                                        .addAnnotation(new NormalAnnotationExpr().addPair("value", new StringLiteralExpr(processorManager.getQualifiedName(annotationExpr))).setName(Named.class.getSimpleName()));

                                                CompilationUnit constructInterceptorCompilationUnit = new CompilationUnit()
                                                        .addType(constructInterceptorDeclaration)
                                                        .addImport(ConstructInterceptor.class)
                                                        .addImport(BeanContext.class)
                                                        .addImport(InvocationContextProxy.class)
                                                        .addImport(processorManager.getQualifiedName(interceptorClassDeclaration))
                                                        .addImport(Dependent.class)
                                                        .addImport(Named.class);

                                                constructInterceptorCompilationUnit.addImport(Priority.class);
                                                interceptorClassDeclaration.getAnnotationByClass(Priority.class)
                                                        .ifPresent(constructInterceptorDeclaration::addAnnotation);

                                                compilationUnit.getPackageDeclaration()
                                                        .ifPresent(constructInterceptorCompilationUnit::setPackageDeclaration);

                                                processorManager.importAllClassOrInterfaceType(constructInterceptorDeclaration, interceptorClassDeclaration);

                                                FieldDeclaration invocationContext = new FieldDeclaration()
                                                        .setModifiers(Modifier.Keyword.PRIVATE)
                                                        .addVariable(
                                                                new VariableDeclarator()
                                                                        .setName("invocationContext")
                                                                        .setType(InvocationContext.class)
                                                                        .setInitializer(
                                                                                new MethodCallExpr()
                                                                                        .setName("setOwner")
                                                                                        .addArgument(new ClassExpr().setType(processorManager.getQualifiedName(annotationExpr)))
                                                                                        .setScope(new ObjectCreationExpr().setType(InvocationContextProxy.class))
                                                                        )
                                                        );
                                                constructInterceptorDeclaration.addMember(invocationContext);

                                                MethodDeclaration getContext = new MethodDeclaration()
                                                        .setName("getContext")
                                                        .setModifiers(Modifier.Keyword.PUBLIC)
                                                        .setType(InvocationContext.class)
                                                        .setBody(
                                                                new BlockStmt()
                                                                        .addStatement(
                                                                                new ReturnStmt()
                                                                                        .setExpression(
                                                                                                new NameExpr("invocationContext")
                                                                                        )
                                                                        )
                                                        );
                                                constructInterceptorDeclaration.addMember(getContext);

                                                MethodDeclaration aroundConstruct = new MethodDeclaration()
                                                        .setName("aroundConstruct")
                                                        .setModifiers(Modifier.Keyword.PUBLIC)
                                                        .setType(Object.class)
                                                        .addParameter(InvocationContext.class, "invocationContext")
                                                        .addAnnotation(Override.class)
                                                        .setBody(
                                                                new BlockStmt()
                                                                        .addStatement(
                                                                                new ReturnStmt()
                                                                                        .setExpression(
                                                                                                new MethodCallExpr()
                                                                                                        .setName(methodDeclaration.getNameAsString())
                                                                                                        .addArgument("invocationContext")
                                                                                                        .setScope(
                                                                                                                new MethodCallExpr()
                                                                                                                        .setName("get")
                                                                                                                        .setScope(
                                                                                                                                new MethodCallExpr("getProvider")
                                                                                                                                        .setScope(new NameExpr("BeanContext"))
                                                                                                                                        .addArgument(new ClassExpr().setType(interceptorClassDeclaration.getNameAsString()))
                                                                                                                        )
                                                                                                        )
                                                                                        )
                                                                        )
                                                        );
                                                constructInterceptorDeclaration.addMember(aroundConstruct);

                                                return constructInterceptorCompilationUnit;
                                            })
                            );
                });
    }
}
