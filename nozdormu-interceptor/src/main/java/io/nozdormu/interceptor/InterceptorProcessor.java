package io.nozdormu.interceptor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.google.auto.service.AutoService;
import io.nozdormu.common.ProcessorManager;
import io.nozdormu.inject.processor.InjectProcessor;
import io.nozdormu.spi.context.BeanContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.interceptor.*;
import jakarta.transaction.TransactionScoped;

import javax.annotation.processing.*;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static javax.lang.model.SourceVersion.RELEASE_11;

@SupportedAnnotationTypes({
        "jakarta.interceptor.Interceptor"
})
@SupportedSourceVersion(RELEASE_11)
@AutoService(Processor.class)
public class InterceptorProcessor extends AbstractProcessor {

    private ProcessorManager processorManager;

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
                                            }
                                    )
                                    .flatMap(annotationExpr ->
                                            interceptorClassDeclaration.getMethods().stream()
                                                    .filter(methodDeclaration -> methodDeclaration.isAnnotationPresent(AroundInvoke.class))
                                                    .map(methodDeclaration -> {
                                                                String name = interceptorClassDeclaration.getNameAsString() + annotationExpr.getNameAsString() + "_" + methodDeclaration.getNameAsString() + interceptorClassDeclaration.getMethods().indexOf(methodDeclaration) + "InvokeInterceptor";
                                                                ClassOrInterfaceDeclaration invokeInterceptorDeclaration = new ClassOrInterfaceDeclaration()
                                                                        .addModifier(Modifier.Keyword.PUBLIC)
                                                                        .addImplementedType(InvokeInterceptor.class)
                                                                        .setName(name)
                                                                        .addAnnotation(new NormalAnnotationExpr().addPair("value", new StringLiteralExpr(processorManager.getQualifiedName(annotationExpr))).setName(Named.class.getSimpleName()));

                                                                CompilationUnit invokeInterceptorCompilationUnit = new CompilationUnit()
                                                                        .addType(invokeInterceptorDeclaration)
                                                                        .addImport(InvokeInterceptor.class)
                                                                        .addImport(BeanContext.class)
                                                                        .addImport(InvocationContextProxy.class)
                                                                        .addImport(processorManager.getQualifiedName(interceptorClassDeclaration))
                                                                        .addImport(Named.class);

                                                                Stream.of(Singleton.class, Dependent.class, ApplicationScoped.class, RequestScoped.class, SessionScoped.class, TransactionScoped.class, Priority.class)
                                                                        .forEach(aClass ->
                                                                                interceptorClassDeclaration.getAnnotationByClass(aClass)
                                                                                        .ifPresent(aExpr -> {
                                                                                                    invokeInterceptorDeclaration.addAnnotation(aExpr.clone());
                                                                                                    invokeInterceptorCompilationUnit.addImport(aClass);
                                                                                                }
                                                                                        )

                                                                        );

                                                                compilationUnit.getPackageDeclaration()
                                                                        .map(PackageDeclaration::clone)
                                                                        .ifPresent(invokeInterceptorCompilationUnit::setPackageDeclaration);

                                                                invokeInterceptorDeclaration.setParentNode(invokeInterceptorCompilationUnit);
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
                                                                                                        .addArgument(new ClassExpr().setType(annotationExpr.getName().getIdentifier()))
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

                                                                return invokeInterceptorCompilationUnit;
                                                            }
                                                    )
                                    );
                        }
                );
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
                                            }
                                    )
                                    .flatMap(annotationExpr ->
                                            interceptorClassDeclaration.getMethods().stream()
                                                    .filter(methodDeclaration -> methodDeclaration.isAnnotationPresent(AroundConstruct.class))
                                                    .map(methodDeclaration -> {
                                                                String name = interceptorClassDeclaration.getNameAsString() + annotationExpr.getNameAsString() + "_" + methodDeclaration.getNameAsString() + interceptorClassDeclaration.getMethods().indexOf(methodDeclaration) + "ConstructInterceptor";
                                                                ClassOrInterfaceDeclaration constructInterceptorDeclaration = new ClassOrInterfaceDeclaration()
                                                                        .addModifier(Modifier.Keyword.PUBLIC)
                                                                        .addImplementedType(ConstructInterceptor.class)
                                                                        .setName(name)
                                                                        .addAnnotation(new NormalAnnotationExpr().addPair("value", new StringLiteralExpr(processorManager.getQualifiedName(annotationExpr))).setName(Named.class.getSimpleName()));

                                                                CompilationUnit constructInterceptorCompilationUnit = new CompilationUnit()
                                                                        .addType(constructInterceptorDeclaration)
                                                                        .addImport(ConstructInterceptor.class)
                                                                        .addImport(BeanContext.class)
                                                                        .addImport(InvocationContextProxy.class)
                                                                        .addImport(processorManager.getQualifiedName(interceptorClassDeclaration))
                                                                        .addImport(Named.class);

                                                                Stream.of(Singleton.class, Dependent.class, ApplicationScoped.class, RequestScoped.class, SessionScoped.class, TransactionScoped.class, Priority.class)
                                                                        .forEach(aClass ->
                                                                                interceptorClassDeclaration.getAnnotationByClass(aClass)
                                                                                        .ifPresent(aExpr -> {
                                                                                                    constructInterceptorDeclaration.addAnnotation(aExpr.clone());
                                                                                                    constructInterceptorCompilationUnit.addImport(aClass);
                                                                                                }
                                                                                        )

                                                                        );

                                                                compilationUnit.getPackageDeclaration()
                                                                        .map(PackageDeclaration::clone)
                                                                        .ifPresent(constructInterceptorCompilationUnit::setPackageDeclaration);

                                                                constructInterceptorDeclaration.setParentNode(constructInterceptorCompilationUnit);
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
                                                                                                        .addArgument(new ClassExpr().setType(annotationExpr.getName().getIdentifier()))
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
                                                            }
                                                    )
                                    );
                        }
                );
    }
}
