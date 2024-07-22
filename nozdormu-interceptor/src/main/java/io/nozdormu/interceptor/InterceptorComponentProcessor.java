package io.nozdormu.interceptor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.ast.type.UnknownType;
import com.google.auto.service.AutoService;
import io.nozdormu.common.ProcessorManager;
import io.nozdormu.spi.context.BeanContext;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuple3;
import io.nozdormu.inject.processor.ComponentProxyProcessor;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Produces;
import jakarta.interceptor.*;
import org.tinylog.Logger;
import reactor.util.function.Tuples;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@AutoService(ComponentProxyProcessor.class)
public class InterceptorComponentProcessor implements ComponentProxyProcessor {

    private ProcessorManager processorManager;
    private Map<String, Set<String>> interceptorAnnotationMap = new HashMap<>();
    private final List<String> processed = new ArrayList<>();

    @Override
    public void init(ProcessorManager processorManager) {
        this.processorManager = processorManager;
    }

    private void registerAnnotation(String annotationName, String interceptorName) {
        interceptorAnnotationMap.computeIfAbsent(annotationName, (key) -> new HashSet<>()).add(interceptorName);
    }

    @Override
    public void inProcess() {
        processorManager.getCompilationUnitListWithAnnotationClass(Interceptor.class).stream()
                .flatMap(compilationUnit -> {
                            ClassOrInterfaceDeclaration classOrInterfaceDeclaration = processorManager.getPublicClassOrInterfaceDeclarationOrError(compilationUnit);
                            return getAspectAnnotationNameList(compilationUnit, true).stream()
                                    .filter(annotationName -> processed.stream().noneMatch(key -> key.equals(annotationName)))
                                    .map(annotationName -> Tuples.of(annotationName, processorManager.getQualifiedName(classOrInterfaceDeclaration)));
                        }
                )
                .collect(Collectors.groupingBy(Tuple2::getT1, Collectors.mapping(Tuple2::getT2, Collectors.toSet())))
                .forEach((key, value) -> {
                            processorManager.getResource("META-INF/interceptor/" + key)
                                    .ifPresent(fileObject -> {
                                                try (InputStream inputStream = fileObject.openInputStream();
                                                     InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
                                                     BufferedReader bufferedReader = new BufferedReader(inputStreamReader)) {
                                                    String line;
                                                    while ((line = bufferedReader.readLine()) != null) {
                                                        Logger.info("add interceptor {} to annotation {}", line, key);
                                                        value.add(line);
                                                    }
                                                } catch (IOException e) {
                                                    Logger.warn(e);
                                                }
                                            }
                                    );
                            processorManager.createResource("META-INF/interceptor/" + key, String.join(System.lineSeparator(), value));
                            processed.add(key);
                            Logger.info("annotation interceptor resource build success: {}", key);
                        }
                );
    }

    @Override
    public void processComponentProxy(CompilationUnit componentCompilationUnit, ClassOrInterfaceDeclaration componentClassDeclaration, CompilationUnit componentProxyCompilationUnit, ClassOrInterfaceDeclaration componentProxyClassDeclaration) {
        this.interceptorAnnotationMap = processorManager.getCompilationUnitListWithAnnotationClass(Interceptor.class).stream()
                .flatMap(compilationUnit -> {
                            ClassOrInterfaceDeclaration classOrInterfaceDeclaration = processorManager.getPublicClassOrInterfaceDeclarationOrError(compilationUnit);
                            return getAspectAnnotationNameList(compilationUnit, true).stream().map(annotationName -> Tuples.of(annotationName, processorManager.getQualifiedName(classOrInterfaceDeclaration)));
                        }
                )
                .collect(Collectors.groupingBy(Tuple2::getT1, Collectors.mapping(Tuple2::getT2, Collectors.toSet())));

        registerMetaInf();
        Set<String> annotationNameList = this.interceptorAnnotationMap.values().stream()
                .flatMap(Collection::stream)
                .map(className -> processorManager.getCompilationUnitOrError(className))
                .flatMap(compilationUnit -> getAspectAnnotationNameList(compilationUnit, false).stream())
                .collect(Collectors.toSet());

        buildMethod(annotationNameList, componentClassDeclaration, componentProxyCompilationUnit, componentProxyClassDeclaration);
        buildConstructor(annotationNameList, componentClassDeclaration, componentProxyCompilationUnit, componentProxyClassDeclaration);
    }

    private void buildMethod(Set<String> annotationNameList, ClassOrInterfaceDeclaration componentClassDeclaration, CompilationUnit componentProxyCompilationUnit, ClassOrInterfaceDeclaration componentProxyClassDeclaration) {
        componentClassDeclaration.getMethods()
                .forEach(methodDeclaration -> {
                            if (methodDeclaration.getAnnotations().stream()
                                    .map(annotationExpr -> processorManager.getQualifiedName(annotationExpr))
                                    .anyMatch(annotationNameList::contains)
                            ) {
                                componentProxyCompilationUnit
                                        .addImport(InvokeInterceptor.class)
                                        .addImport(InvocationContext.class)
                                        .addImport(InvocationContextProxy.class)
                                        .addImport(BeanContext.class)
                                        .addImport(Optional.class)
                                        .addImport(Map.class);

                                MethodDeclaration overrideMethodDeclaration = componentProxyClassDeclaration.addMethod(methodDeclaration.getNameAsString())
                                        .setModifiers(methodDeclaration.getModifiers().stream().map(Modifier::clone).collect(Collectors.toCollection(NodeList::new)))
                                        .setParameters(methodDeclaration.getParameters().stream().map(Parameter::clone).collect(Collectors.toCollection(NodeList::new)))
                                        .setType(methodDeclaration.getType().clone())
                                        .addAnnotation(Override.class);

                                methodDeclaration.getTypeParameters().stream()
                                        .map(TypeParameter::clone)
                                        .forEach(overrideMethodDeclaration::addTypeParameter);

                                String proxyMethodName = methodDeclaration.getNameAsString() +
                                        "_" +
                                        methodDeclaration.getParameters().stream()
                                                .map(parameter -> parameter.getType().isClassOrInterfaceType() ? parameter.getType().asClassOrInterfaceType().getNameAsString() : parameter.getType().asString())
                                                .collect(Collectors.joining("_")) +
                                        "_Proxy";

                                MethodDeclaration proxyMethodDeclaration = componentProxyClassDeclaration.addMethod(proxyMethodName)
                                        .setModifiers(methodDeclaration.getModifiers().stream().map(Modifier::clone).collect(Collectors.toCollection(NodeList::new)))
                                        .addParameter(InvocationContext.class, "invocationContext");

                                methodDeclaration.getTypeParameters().stream()
                                        .map(TypeParameter::clone)
                                        .forEach(proxyMethodDeclaration::addTypeParameter);

                                VariableDeclarationExpr invocationContextProxyVariable = new VariableDeclarationExpr()
                                        .addVariable(new VariableDeclarator()
                                                .setType(InvocationContextProxy.class)
                                                .setName("invocationContextProxy")
                                                .setInitializer(new CastExpr()
                                                        .setType(InvocationContextProxy.class)
                                                        .setExpression(new NameExpr("invocationContext"))
                                                )
                                        );

                                MethodCallExpr superMethodCallExpr = new MethodCallExpr()
                                        .setName(methodDeclaration.getName().clone())
                                        .setArguments(
                                                methodDeclaration.getParameters().stream()
                                                        .map(Parameter::clone)
                                                        .map(parameter ->
                                                                new CastExpr()
                                                                        .setType(parameter.getType())
                                                                        .setExpression(
                                                                                new MethodCallExpr("getParameterValue")
                                                                                        .setScope(new NameExpr("invocationContextProxy"))
                                                                                        .addArgument(new StringLiteralExpr(parameter.getNameAsString()))
                                                                        )
                                                        )
                                                        .collect(Collectors.toCollection(NodeList::new))
                                        )
                                        .setScope(new SuperExpr());

                                if (methodDeclaration.getType().isVoidType()) {
                                    proxyMethodDeclaration
                                            .setType(methodDeclaration.getType().clone())
                                            .createBody()
                                            .addStatement(
                                                    new TryStmt()
                                                            .setTryBlock(
                                                                    new BlockStmt()
                                                                            .addStatement(invocationContextProxyVariable)
                                                                            .addStatement(superMethodCallExpr)
                                                            )
                                                            .setCatchClauses(
                                                                    NodeList.nodeList(
                                                                            new CatchClause()
                                                                                    .setParameter(
                                                                                            new Parameter()
                                                                                                    .setType(Exception.class)
                                                                                                    .setName("e")
                                                                                    )
                                                                                    .setBody(
                                                                                            new BlockStmt()
                                                                                                    .addStatement(
                                                                                                            new ThrowStmt()
                                                                                                                    .setExpression(
                                                                                                                            new ObjectCreationExpr()
                                                                                                                                    .setType(RuntimeException.class)
                                                                                                                                    .addArgument("e")
                                                                                                                    )
                                                                                                    )
                                                                                    )
                                                                    )
                                                            )
                                            );
                                } else {
                                    proxyMethodDeclaration
                                            .setType(Object.class)
                                            .createBody()
                                            .addStatement(
                                                    new TryStmt()
                                                            .setTryBlock(
                                                                    new BlockStmt()
                                                                            .addStatement(invocationContextProxyVariable)
                                                                            .addStatement(new ReturnStmt(superMethodCallExpr))
                                                            )
                                                            .setCatchClauses(
                                                                    NodeList.nodeList(
                                                                            new CatchClause()
                                                                                    .setParameter(
                                                                                            new Parameter()
                                                                                                    .setType(Exception.class)
                                                                                                    .setName("e")
                                                                                    )
                                                                                    .setBody(
                                                                                            new BlockStmt()
                                                                                                    .addStatement(
                                                                                                            new ThrowStmt()
                                                                                                                    .setExpression(
                                                                                                                            new ObjectCreationExpr()
                                                                                                                                    .setType(RuntimeException.class)
                                                                                                                                    .addArgument("e")
                                                                                                                    )
                                                                                                    )
                                                                                    )
                                                                    )
                                                            )
                                            );
                                }
                                BlockStmt body = overrideMethodDeclaration.getBody().orElseGet(overrideMethodDeclaration::createBody);
                                List<AnnotationExpr> annotationExprList = methodDeclaration.getAnnotations().stream()
                                        .filter(annotationExpr -> annotationNameList.contains(processorManager.getQualifiedName(annotationExpr)))
                                        .collect(Collectors.toList());

                                componentProxyClassDeclaration
                                        .addMember(
                                                new FieldDeclaration()
                                                        .setModifiers(Modifier.Keyword.PRIVATE, Modifier.Keyword.FINAL)
                                                        .addVariable(
                                                                new VariableDeclarator()
                                                                        .setType(
                                                                                new ClassOrInterfaceType()
                                                                                        .setName("List")
                                                                                        .setTypeArguments(
                                                                                                new ClassOrInterfaceType()
                                                                                                        .setName("InvokeInterceptor")
                                                                                        )
                                                                        )
                                                                        .setName(methodDeclaration.getNameAsString() + "InvokeInterceptorList")
                                                                        .setInitializer(
                                                                                new MethodCallExpr()
                                                                                        .setName("getInvokeInterceptorList")
                                                                                        .setArguments(
                                                                                                annotationExprList.stream()
                                                                                                        .map(annotationExpr -> (Expression) new StringLiteralExpr(processorManager.getQualifiedName(annotationExpr)))
                                                                                                        .collect(Collectors.toCollection(NodeList::new))
                                                                                        )
                                                                                        .setScope(new NameExpr("InvokeInterceptor"))
                                                                        )
                                                        )
                                        );

                                VariableDeclarator ownerValueMap = new VariableDeclarator()
                                        .setType(
                                                new ClassOrInterfaceType()
                                                        .setName("Map")
                                                        .setTypeArguments(
                                                                new ClassOrInterfaceType().setName("String"),
                                                                new ClassOrInterfaceType()
                                                                        .setName("Map")
                                                                        .setTypeArguments(
                                                                                new ClassOrInterfaceType().setName("String"),
                                                                                new ClassOrInterfaceType().setName("Object")
                                                                        )
                                                        )
                                        )
                                        .setName("ownerValueMap")
                                        .setInitializer(
                                                new MethodCallExpr()
                                                        .setName("of")
                                                        .setArguments(
                                                                annotationExprList.stream()
                                                                        .flatMap(annotationExpr -> {
                                                                                    if (annotationExpr.isNormalAnnotationExpr()) {
                                                                                        return Stream.of(
                                                                                                new StringLiteralExpr(processorManager.getQualifiedName(annotationExpr)),
                                                                                                new MethodCallExpr()
                                                                                                        .setName("of")
                                                                                                        .setArguments(
                                                                                                                annotationExpr.asNormalAnnotationExpr().getPairs().stream()
                                                                                                                        .flatMap(memberValuePair ->
                                                                                                                                Stream.of(new StringLiteralExpr(memberValuePair.getNameAsString()), memberValuePair.getValue())
                                                                                                                        )
                                                                                                                        .collect(Collectors.toCollection(NodeList::new))
                                                                                                        )
                                                                                                        .setScope(new NameExpr("Map"))
                                                                                        );
                                                                                    } else if (annotationExpr.isSingleMemberAnnotationExpr()) {

                                                                                        return Stream.of(
                                                                                                new StringLiteralExpr(processorManager.getQualifiedName(annotationExpr)), new MethodCallExpr()
                                                                                                        .setName("of")
                                                                                                        .addArgument(new StringLiteralExpr("value"))
                                                                                                        .addArgument(annotationExpr.asSingleMemberAnnotationExpr().getMemberValue())
                                                                                                        .setScope(new NameExpr("Map"))
                                                                                        );
                                                                                    } else {
                                                                                        return Stream.of(
                                                                                                new StringLiteralExpr(processorManager.getQualifiedName(annotationExpr)), new MethodCallExpr()
                                                                                                        .setName("of")
                                                                                                        .setScope(new NameExpr("Map"))
                                                                                        );
                                                                                    }
                                                                                }
                                                                        )
                                                                        .collect(Collectors.toCollection(NodeList::new))
                                                        )
                                                        .setScope(new NameExpr("Map"))
                                        );
                                body.addStatement(new VariableDeclarationExpr().addVariable(ownerValueMap));

                                StringLiteralExpr methodName = new StringLiteralExpr(methodDeclaration.getNameAsString());
                                IntegerLiteralExpr methodParameterCount = new IntegerLiteralExpr(String.valueOf(methodDeclaration.getParameters().size()));
                                ArrayCreationExpr methodParameterTypeNames = new ArrayCreationExpr().setElementType(String.class)
                                        .setInitializer(
                                                new ArrayInitializerExpr(
                                                        methodDeclaration.getParameters().stream()
                                                                .map(parameter -> parameter.getType().isClassOrInterfaceType() ? processorManager.getQualifiedName(parameter.getType().asClassOrInterfaceType()) : parameter.getType().asString())
                                                                .map(StringLiteralExpr::new)
                                                                .collect(Collectors.toCollection(NodeList::new))
                                                )
                                        );

                                MethodCallExpr setTarget = new MethodCallExpr()
                                        .setName("setOwnerValues")
                                        .addArgument(
                                                new MethodCallExpr()
                                                        .setName("get")
                                                        .addArgument(
                                                                new MethodCallExpr()
                                                                        .setName("getName")
                                                                        .setScope(
                                                                                new MethodCallExpr()
                                                                                        .setName("getOwner")
                                                                                        .setScope(
                                                                                                new MethodCallExpr()
                                                                                                        .setName("getContextProxy")
                                                                                                        .setScope(new NameExpr("cur"))
                                                                                        )
                                                                        )
                                                        )
                                                        .setScope(new NameExpr("ownerValueMap"))
                                        )
                                        .setScope(
                                                new MethodCallExpr()
                                                        .setName("setTarget")
                                                        .addArgument(new ThisExpr())
                                                        .setScope(
                                                                new MethodCallExpr()
                                                                        .setName("getContextProxy")
                                                                        .setScope(new NameExpr("cur"))
                                                        )
                                        );

                                body
                                        .addStatement(
                                                new ReturnStmt()
                                                        .setExpression(

                                                                new MethodCallExpr()
                                                                        .setName("orElseGet")
                                                                        .addArgument(
                                                                                new LambdaExpr()
                                                                                        .setEnclosingParameters(true)
                                                                                        .setBody(
                                                                                                new ExpressionStmt()
                                                                                                        .setExpression(
                                                                                                                new MethodCallExpr()
                                                                                                                        .setName(methodDeclaration.getNameAsString())
                                                                                                                        .setArguments(
                                                                                                                                methodDeclaration.getParameters().stream()
                                                                                                                                        .map(NodeWithSimpleName::getNameAsExpression)
                                                                                                                                        .collect(Collectors.toCollection(NodeList::new))
                                                                                                                        )
                                                                                                                        .setScope(new SuperExpr())
                                                                                                        )
                                                                                        )
                                                                        )
                                                                        .setScope(
                                                                                new MethodCallExpr()
                                                                                        .setName("map")
                                                                                        .addArgument(
                                                                                                new LambdaExpr()
                                                                                                        .addParameter(new UnknownType(), "invokeInterceptor")
                                                                                                        .setBody(
                                                                                                                new ExpressionStmt()
                                                                                                                        .setExpression(
                                                                                                                                new CastExpr()
                                                                                                                                        .setType(methodDeclaration.getType().clone())
                                                                                                                                        .setExpression(
                                                                                                                                                new MethodCallExpr()
                                                                                                                                                        .setName("aroundInvoke")
                                                                                                                                                        .addArgument(
                                                                                                                                                                new MethodCallExpr()
                                                                                                                                                                        .setName("getContext")
                                                                                                                                                                        .setScope(new NameExpr("invokeInterceptor"))
                                                                                                                                                        )
                                                                                                                                                        .setScope(
                                                                                                                                                                new NameExpr("invokeInterceptor")
                                                                                                                                                        )
                                                                                                                                        )
                                                                                                                        )
                                                                                                        )
                                                                                        )
                                                                                        .setScope(
                                                                                                new MethodCallExpr()
                                                                                                        .setName("ofNullable")
                                                                                                        .addArgument(
                                                                                                                new MethodCallExpr()
                                                                                                                        .setName("reduce")
                                                                                                                        .addArgument(new NullLiteralExpr())
                                                                                                                        .addArgument(
                                                                                                                                new LambdaExpr()
                                                                                                                                        .addParameter(new UnknownType(), "pre")
                                                                                                                                        .addParameter(new UnknownType(), "cur")
                                                                                                                                        .setEnclosingParameters(true)
                                                                                                                                        .setBody(
                                                                                                                                                new BlockStmt()
                                                                                                                                                        .addStatement(
                                                                                                                                                                new IfStmt()
                                                                                                                                                                        .setCondition(
                                                                                                                                                                                new BinaryExpr()
                                                                                                                                                                                        .setLeft(new NameExpr("pre"))
                                                                                                                                                                                        .setOperator(BinaryExpr.Operator.NOT_EQUALS)
                                                                                                                                                                                        .setRight(new NullLiteralExpr())
                                                                                                                                                                        )
                                                                                                                                                                        .setThenStmt(
                                                                                                                                                                                new BlockStmt()
                                                                                                                                                                                        .addStatement(
                                                                                                                                                                                                new MethodCallExpr()
                                                                                                                                                                                                        .setName("setNextInvocationContext")
                                                                                                                                                                                                        .addArgument(
                                                                                                                                                                                                                new MethodCallExpr()
                                                                                                                                                                                                                        .setName("getContext")
                                                                                                                                                                                                                        .setScope(new NameExpr("pre"))
                                                                                                                                                                                                        )
                                                                                                                                                                                                        .setScope(
                                                                                                                                                                                                                new MethodCallExpr()
                                                                                                                                                                                                                        .setName("setNextProceed")
                                                                                                                                                                                                                        .addArgument(
                                                                                                                                                                                                                                new MethodReferenceExpr()
                                                                                                                                                                                                                                        .setIdentifier("aroundInvoke")
                                                                                                                                                                                                                                        .setScope(new NameExpr("pre"))
                                                                                                                                                                                                                        )
                                                                                                                                                                                                                        .setScope(
                                                                                                                                                                                                                                new MethodCallExpr()
                                                                                                                                                                                                                                        .setName("setMethod")
                                                                                                                                                                                                                                        .addArgument(methodName)
                                                                                                                                                                                                                                        .addArgument(methodParameterCount)
                                                                                                                                                                                                                                        .addArgument(methodParameterTypeNames)
                                                                                                                                                                                                                                        .setScope(
                                                                                                                                                                                                                                                methodDeclaration.getParameters().stream()
                                                                                                                                                                                                                                                        .map(Parameter::clone)
                                                                                                                                                                                                                                                        .map(parameter -> (Expression) parameter.getNameAsExpression())
                                                                                                                                                                                                                                                        .reduce(setTarget, (left, right) ->
                                                                                                                                                                                                                                                                new MethodCallExpr("addParameterValue")
                                                                                                                                                                                                                                                                        .addArgument(new StringLiteralExpr(right.asNameExpr().getNameAsString()))
                                                                                                                                                                                                                                                                        .addArgument(right)
                                                                                                                                                                                                                                                                        .setScope(left)
                                                                                                                                                                                                                                                        )
                                                                                                                                                                                                                                        )
                                                                                                                                                                                                                        )
                                                                                                                                                                                                        )


                                                                                                                                                                                        )
                                                                                                                                                                        )
                                                                                                                                                                        .setElseStmt(
                                                                                                                                                                                new BlockStmt()
                                                                                                                                                                                        .addStatement(
                                                                                                                                                                                                new MethodCallExpr()
                                                                                                                                                                                                        .setName(methodDeclaration.getType().isVoidType() ? "setConsumer" : "setFunction")
                                                                                                                                                                                                        .addArgument(
                                                                                                                                                                                                                new MethodReferenceExpr()
                                                                                                                                                                                                                        .setIdentifier(proxyMethodName)
                                                                                                                                                                                                                        .setScope(methodDeclaration.isStatic() ? new NameExpr(componentProxyClassDeclaration.getNameAsString()) : new ThisExpr())
                                                                                                                                                                                                        )
                                                                                                                                                                                                        .setScope(setTarget)
                                                                                                                                                                                        )
                                                                                                                                                                        )
                                                                                                                                                        )
                                                                                                                                                        .addStatement(
                                                                                                                                                                new ReturnStmt()
                                                                                                                                                                        .setExpression(new NameExpr("cur"))
                                                                                                                                                        )
                                                                                                                                        )
                                                                                                                        )
                                                                                                                        .setScope(
                                                                                                                                new MethodCallExpr()
                                                                                                                                        .setName("stream")
                                                                                                                                        .setScope(new NameExpr(methodDeclaration.getNameAsString() + "InvokeInterceptorList"))
                                                                                                                        )
                                                                                                        )
                                                                                                        .setScope(new NameExpr("Optional"))
                                                                                        )
                                                                        )
                                                        )
                                        );
                            }
                        }
                );
    }

    private void buildConstructor(Set<String> annotationNameList, ClassOrInterfaceDeclaration componentClassDeclaration, CompilationUnit componentProxyCompilationUnit, ClassOrInterfaceDeclaration componentProxyClassDeclaration) {
        componentClassDeclaration.getConstructors()
                .forEach(constructorDeclaration -> {
                            if (constructorDeclaration.getAnnotations().stream()
                                    .map(annotationExpr -> processorManager.getQualifiedName(annotationExpr))
                                    .anyMatch(annotationNameList::contains)
                            ) {
                                componentProxyCompilationUnit
                                        .addImport(ConstructInterceptor.class)
                                        .addImport(InvocationContext.class)
                                        .addImport(InvocationContextProxy.class)
                                        .addImport(BeanContext.class)
                                        .addImport(Optional.class)
                                        .addImport(Map.class);

                                MethodDeclaration creatorMethod = componentProxyClassDeclaration.addMethod("create" + componentProxyClassDeclaration.getNameAsString())
                                        .setModifiers(Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC)
                                        .setType(componentProxyClassDeclaration.getNameAsString())
                                        .setParameters(constructorDeclaration.getParameters())
                                        .addAnnotation(Produces.class)
                                        .setThrownExceptions(constructorDeclaration.getThrownExceptions());

                                MethodDeclaration invocationCreatorMethod = componentProxyClassDeclaration.addMethod("create" + componentProxyClassDeclaration.getNameAsString())
                                        .setModifiers(Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC)
                                        .setType(Object.class)
                                        .addParameter(InvocationContext.class, "invocationContext");

                                VariableDeclarationExpr invocationContextProxyVariable = new VariableDeclarationExpr()
                                        .addVariable(new VariableDeclarator()
                                                .setType(InvocationContextProxy.class)
                                                .setName("invocationContextProxy")
                                                .setInitializer(new CastExpr()
                                                        .setType(InvocationContextProxy.class)
                                                        .setExpression(new NameExpr("invocationContext"))
                                                )
                                        );

                                ObjectCreationExpr invocationCreatorMethodCallExpr = new ObjectCreationExpr()
                                        .setType(componentProxyClassDeclaration.getNameAsString())
                                        .setArguments(
                                                constructorDeclaration.getParameters().stream()
                                                        .map(parameter ->
                                                                new CastExpr()
                                                                        .setType(parameter.getType())
                                                                        .setExpression(
                                                                                new MethodCallExpr("getParameterValue")
                                                                                        .setScope(new NameExpr("invocationContextProxy"))
                                                                                        .addArgument(new StringLiteralExpr(parameter.getNameAsString()))
                                                                        )
                                                        )
                                                        .collect(Collectors.toCollection(NodeList::new))
                                        );

                                invocationCreatorMethod.createBody()
                                        .addStatement(
                                                new TryStmt()
                                                        .setTryBlock(
                                                                new BlockStmt()
                                                                        .addStatement(invocationContextProxyVariable)
                                                                        .addStatement(new ReturnStmt(invocationCreatorMethodCallExpr))
                                                        )
                                                        .setCatchClauses(
                                                                NodeList.nodeList(
                                                                        new CatchClause()
                                                                                .setParameter(
                                                                                        new Parameter()
                                                                                                .setType(Exception.class)
                                                                                                .setName("e")
                                                                                )
                                                                                .setBody(
                                                                                        new BlockStmt()
                                                                                                .addStatement(
                                                                                                        new ThrowStmt()
                                                                                                                .setExpression(
                                                                                                                        new ObjectCreationExpr()
                                                                                                                                .setType(RuntimeException.class)
                                                                                                                                .addArgument("e")
                                                                                                                )
                                                                                                )
                                                                                )
                                                                )
                                                        )
                                        );

                                BlockStmt body = creatorMethod.getBody().orElseGet(creatorMethod::createBody);

                                List<AnnotationExpr> annotationExprList = constructorDeclaration.getAnnotations().stream()
                                        .filter(annotationExpr -> annotationNameList.contains(processorManager.getQualifiedName(annotationExpr)))
                                        .collect(Collectors.toList());

                                componentProxyClassDeclaration
                                        .addMember(
                                                new FieldDeclaration()
                                                        .setModifiers(Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC, Modifier.Keyword.FINAL)
                                                        .addVariable(
                                                                new VariableDeclarator()
                                                                        .setType(
                                                                                new ClassOrInterfaceType()
                                                                                        .setName("List")
                                                                                        .setTypeArguments(
                                                                                                new ClassOrInterfaceType()
                                                                                                        .setName("ConstructInterceptor")
                                                                                        )
                                                                        )
                                                                        .setName("create" + componentProxyClassDeclaration.getNameAsString() + "ConstructInterceptorList")
                                                                        .setInitializer(
                                                                                new MethodCallExpr()
                                                                                        .setName("getConstructInterceptorList")
                                                                                        .setArguments(
                                                                                                annotationExprList.stream()
                                                                                                        .map(annotationExpr -> (Expression) new StringLiteralExpr(processorManager.getQualifiedName(annotationExpr)))
                                                                                                        .collect(Collectors.toCollection(NodeList::new))
                                                                                        )
                                                                                        .setScope(new NameExpr("ConstructInterceptor"))
                                                                        )
                                                        )
                                        );

                                VariableDeclarator ownerValueMap = new VariableDeclarator()
                                        .setType(
                                                new ClassOrInterfaceType()
                                                        .setName("Map")
                                                        .setTypeArguments(
                                                                new ClassOrInterfaceType().setName("String"),
                                                                new ClassOrInterfaceType()
                                                                        .setName("Map")
                                                                        .setTypeArguments(
                                                                                new ClassOrInterfaceType().setName("String"),
                                                                                new ClassOrInterfaceType().setName("Object")
                                                                        )
                                                        )
                                        )
                                        .setName("ownerValueMap")
                                        .setInitializer(
                                                new MethodCallExpr()
                                                        .setName("of")
                                                        .setArguments(
                                                                annotationExprList.stream()
                                                                        .flatMap(annotationExpr -> {
                                                                                    if (annotationExpr.isNormalAnnotationExpr()) {
                                                                                        return Stream.of(
                                                                                                new StringLiteralExpr(processorManager.getQualifiedName(annotationExpr)),
                                                                                                new MethodCallExpr()
                                                                                                        .setName("of")
                                                                                                        .setArguments(
                                                                                                                annotationExpr.asNormalAnnotationExpr().getPairs().stream()
                                                                                                                        .flatMap(memberValuePair ->
                                                                                                                                Stream.of(new StringLiteralExpr(memberValuePair.getNameAsString()), memberValuePair.getValue())
                                                                                                                        )
                                                                                                                        .collect(Collectors.toCollection(NodeList::new))
                                                                                                        )
                                                                                                        .setScope(new NameExpr("Map"))
                                                                                        );
                                                                                    } else if (annotationExpr.isSingleMemberAnnotationExpr()) {

                                                                                        return Stream.of(
                                                                                                new StringLiteralExpr(processorManager.getQualifiedName(annotationExpr)), new MethodCallExpr()
                                                                                                        .setName("of")
                                                                                                        .addArgument(new StringLiteralExpr("value"))
                                                                                                        .addArgument(annotationExpr.asSingleMemberAnnotationExpr().getMemberValue())
                                                                                                        .setScope(new NameExpr("Map"))
                                                                                        );
                                                                                    } else {
                                                                                        return Stream.of(
                                                                                                new StringLiteralExpr(processorManager.getQualifiedName(annotationExpr)), new MethodCallExpr()
                                                                                                        .setName("of")
                                                                                                        .setScope(new NameExpr("Map"))
                                                                                        );
                                                                                    }
                                                                                }
                                                                        )
                                                                        .collect(Collectors.toCollection(NodeList::new))
                                                        )
                                                        .setScope(new NameExpr("Map"))
                                        );
                                body.addStatement(new VariableDeclarationExpr().addVariable(ownerValueMap));

                                IntegerLiteralExpr constructorParameterCount = new IntegerLiteralExpr(String.valueOf(constructorDeclaration.getParameters().size()));
                                ArrayCreationExpr constructorParameterTypeNames = new ArrayCreationExpr().setElementType(String.class)
                                        .setInitializer(
                                                new ArrayInitializerExpr(
                                                        constructorDeclaration.getParameters().stream()
                                                                .map(parameter -> parameter.getType().isClassOrInterfaceType() ? processorManager.getQualifiedName(parameter.getType().asClassOrInterfaceType()) : parameter.getType().asString())
                                                                .map(StringLiteralExpr::new)
                                                                .collect(Collectors.toCollection(NodeList::new))
                                                )
                                        );

                                MethodCallExpr setTarget = new MethodCallExpr()
                                        .setName("setTarget")
                                        .addArgument(new ClassExpr().setType(componentProxyClassDeclaration.getNameAsString()))
                                        .setScope(
                                                new MethodCallExpr()
                                                        .setName("getContextProxy")
                                                        .setScope(new NameExpr("cur"))
                                        );

                                body
                                        .addStatement(
                                                new ReturnStmt()
                                                        .setExpression(
                                                                new MethodCallExpr()
                                                                        .setName("orElseGet")
                                                                        .addArgument(
                                                                                new LambdaExpr()
                                                                                        .setEnclosingParameters(true)
                                                                                        .setBody(
                                                                                                new ExpressionStmt()
                                                                                                        .setExpression(
                                                                                                                new ObjectCreationExpr()
                                                                                                                        .setType(componentProxyClassDeclaration.getNameAsString())
                                                                                                                        .setArguments(
                                                                                                                                constructorDeclaration.getParameters().stream()
                                                                                                                                        .map(NodeWithSimpleName::getNameAsExpression)
                                                                                                                                        .collect(Collectors.toCollection(NodeList::new))
                                                                                                                        )
                                                                                                        )
                                                                                        )
                                                                        )
                                                                        .setScope(
                                                                                new MethodCallExpr()
                                                                                        .setName("map")
                                                                                        .addArgument(
                                                                                                new LambdaExpr()
                                                                                                        .addParameter(new UnknownType(), "constructInterceptor")
                                                                                                        .setBody(
                                                                                                                new ExpressionStmt()
                                                                                                                        .setExpression(
                                                                                                                                new CastExpr()
                                                                                                                                        .setType(componentProxyClassDeclaration.getNameAsString())
                                                                                                                                        .setExpression(
                                                                                                                                                new MethodCallExpr()
                                                                                                                                                        .setName("aroundConstruct")
                                                                                                                                                        .addArgument(
                                                                                                                                                                new MethodCallExpr()
                                                                                                                                                                        .setName("getContext")
                                                                                                                                                                        .setScope(new NameExpr("constructInterceptor"))
                                                                                                                                                        )
                                                                                                                                                        .setScope(
                                                                                                                                                                new NameExpr("constructInterceptor")
                                                                                                                                                        )
                                                                                                                                        )
                                                                                                                        )
                                                                                                        )
                                                                                        )
                                                                                        .setScope(
                                                                                                new MethodCallExpr()
                                                                                                        .setName("ofNullable")
                                                                                                        .addArgument(
                                                                                                                new MethodCallExpr()
                                                                                                                        .setName("reduce")
                                                                                                                        .addArgument(new NullLiteralExpr())
                                                                                                                        .addArgument(
                                                                                                                                new LambdaExpr()
                                                                                                                                        .addParameter(new UnknownType(), "pre")
                                                                                                                                        .addParameter(new UnknownType(), "cur")
                                                                                                                                        .setEnclosingParameters(true)
                                                                                                                                        .setBody(
                                                                                                                                                new BlockStmt()
                                                                                                                                                        .addStatement(
                                                                                                                                                                new IfStmt()
                                                                                                                                                                        .setCondition(
                                                                                                                                                                                new BinaryExpr()
                                                                                                                                                                                        .setLeft(new NameExpr("pre"))
                                                                                                                                                                                        .setOperator(BinaryExpr.Operator.NOT_EQUALS)
                                                                                                                                                                                        .setRight(new NullLiteralExpr())
                                                                                                                                                                        )
                                                                                                                                                                        .setThenStmt(
                                                                                                                                                                                new BlockStmt()
                                                                                                                                                                                        .addStatement(
                                                                                                                                                                                                new MethodCallExpr()
                                                                                                                                                                                                        .setName("setNextInvocationContext")
                                                                                                                                                                                                        .addArgument(
                                                                                                                                                                                                                new MethodCallExpr()
                                                                                                                                                                                                                        .setName("getContext")
                                                                                                                                                                                                                        .setScope(new NameExpr("pre"))
                                                                                                                                                                                                        )
                                                                                                                                                                                                        .setScope(
                                                                                                                                                                                                                new MethodCallExpr()
                                                                                                                                                                                                                        .setName("setNextProceed")
                                                                                                                                                                                                                        .addArgument(
                                                                                                                                                                                                                                new MethodReferenceExpr()
                                                                                                                                                                                                                                        .setIdentifier("aroundConstruct")
                                                                                                                                                                                                                                        .setScope(new NameExpr("pre"))
                                                                                                                                                                                                                        )
                                                                                                                                                                                                                        .setScope(
                                                                                                                                                                                                                                new MethodCallExpr()
                                                                                                                                                                                                                                        .setName("setConstructor")
                                                                                                                                                                                                                                        .addArgument(constructorParameterCount)
                                                                                                                                                                                                                                        .addArgument(constructorParameterTypeNames)
                                                                                                                                                                                                                                        .setScope(
                                                                                                                                                                                                                                                constructorDeclaration.getParameters().stream()
                                                                                                                                                                                                                                                        .map(parameter -> (Expression) parameter.getNameAsExpression())
                                                                                                                                                                                                                                                        .reduce(setTarget, (left, right) ->
                                                                                                                                                                                                                                                                new MethodCallExpr("addParameterValue")
                                                                                                                                                                                                                                                                        .addArgument(new StringLiteralExpr(right.asNameExpr().getNameAsString()))
                                                                                                                                                                                                                                                                        .addArgument(right)
                                                                                                                                                                                                                                                                        .setScope(left)
                                                                                                                                                                                                                                                        )
                                                                                                                                                                                                                                        )
                                                                                                                                                                                                                        )
                                                                                                                                                                                                        )


                                                                                                                                                                                        )
                                                                                                                                                                        )
                                                                                                                                                                        .setElseStmt(
                                                                                                                                                                                new BlockStmt()
                                                                                                                                                                                        .addStatement(new MethodCallExpr()
                                                                                                                                                                                                .setName("setFunction")
                                                                                                                                                                                                .addArgument(
                                                                                                                                                                                                        new MethodReferenceExpr()
                                                                                                                                                                                                                .setIdentifier("create" + componentProxyClassDeclaration.getNameAsString())
                                                                                                                                                                                                                .setScope(new NameExpr(componentProxyClassDeclaration.getNameAsString()))
                                                                                                                                                                                                )
                                                                                                                                                                                                .setScope(setTarget)
                                                                                                                                                                                        )
                                                                                                                                                                        )
                                                                                                                                                        )
                                                                                                                                                        .addStatement(
                                                                                                                                                                new ReturnStmt()
                                                                                                                                                                        .setExpression(new NameExpr("cur"))
                                                                                                                                                        )
                                                                                                                                        )
                                                                                                                        )
                                                                                                                        .setScope(
                                                                                                                                new MethodCallExpr()
                                                                                                                                        .setName("stream")
                                                                                                                                        .setScope(new NameExpr("create" + componentProxyClassDeclaration.getNameAsString() + "ConstructInterceptorList"))
                                                                                                                        )
                                                                                                        )
                                                                                                        .setScope(new NameExpr("Optional"))
                                                                                        )
                                                                        )
                                                        )
                                        );
                            }
                        }
                );
    }

    private List<String> getAspectAnnotationNameList(CompilationUnit compilationUnit, boolean includeInterceptorBinding) {
        ClassOrInterfaceDeclaration classOrInterfaceDeclaration = processorManager.getPublicClassOrInterfaceDeclarationOrError(compilationUnit);

        List<String> annotationExprList = classOrInterfaceDeclaration.getAnnotations().stream()
                .filter(annotationExpr -> {
                            CompilationUnit annotationCompilationUnit = processorManager.getCompilationUnitOrError(annotationExpr);
                            AnnotationDeclaration annotationDeclaration = processorManager.getPublicAnnotationDeclarationOrError(annotationCompilationUnit);
                            return annotationDeclaration.getAnnotations().stream()
                                    .anyMatch(subAnnotationExpr -> !includeInterceptorBinding || processorManager.getQualifiedName(subAnnotationExpr).equals(InterceptorBinding.class.getName()));
                        }
                )
                .map(annotationExpr -> processorManager.getQualifiedName(annotationExpr))
                .collect(Collectors.toList());

        List<String> subAnnotationExprList = classOrInterfaceDeclaration.getAnnotations().stream()
                .filter(annotationExpr -> {
                            CompilationUnit annotationCompilationUnit = processorManager.getCompilationUnitOrError(annotationExpr);
                            AnnotationDeclaration annotationDeclaration = processorManager.getPublicAnnotationDeclarationOrError(annotationCompilationUnit);
                            return annotationDeclaration.getAnnotations().stream()
                                    .anyMatch(subAnnotationExpr -> annotationExprList.contains(processorManager.getQualifiedName(subAnnotationExpr)));
                        }
                )
                .map(annotationExpr -> processorManager.getQualifiedName(annotationExpr))
                .collect(Collectors.toList());

        return Stream.concat(annotationExprList.stream(), subAnnotationExprList.stream()).collect(Collectors.toList());
    }

    public void registerMetaInf() {
        try {
            Iterator<URL> urlIterator = Objects.requireNonNull(InterceptorComponentProcessor.class.getClassLoader().getResources("META-INF/interceptor")).asIterator();
            while (urlIterator.hasNext()) {
                URI uri = urlIterator.next().toURI();
                List<Path> pathList;
                try (Stream<Path> pathStream = Files.list(Path.of(uri))) {
                    pathList = pathStream.collect(Collectors.toList());
                } catch (FileSystemNotFoundException fileSystemNotFoundException) {
                    Map<String, String> env = new HashMap<>();
                    try (FileSystem fileSystem = FileSystems.newFileSystem(uri, env);
                         Stream<Path> pathStream = Files.list(fileSystem.getPath("META-INF/interceptor"))) {
                        pathList = pathStream.collect(Collectors.toList());
                    }
                }
                pathList.forEach(path -> {
                            try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(path.toUri().toURL().openStream()))) {
                                String line;
                                while ((line = bufferedReader.readLine()) != null) {
                                    this.registerAnnotation(path.getFileName().toString(), line);
                                    Logger.info("find interceptor class {} for {}", line, path.getFileName().toString());
                                }
                            } catch (IOException e) {
                                Logger.error(e);
                            }
                        }
                );
            }
        } catch (IllegalArgumentException | URISyntaxException | IOException e) {
            Logger.error(e);
        }
    }

    private List<Tuple3<CompilationUnit, ClassOrInterfaceDeclaration, MethodDeclaration>> getInterceptorMethodList(String annotationName, Class<? extends Annotation> annotationClass) {
        return this.interceptorAnnotationMap.get(annotationName).stream()
                .map(className -> processorManager.getCompilationUnitOrError(className))
                .flatMap(compilationUnit -> {
                            ClassOrInterfaceDeclaration classOrInterfaceDeclaration = processorManager.getPublicClassOrInterfaceDeclarationOrError(compilationUnit);
                            return classOrInterfaceDeclaration.getMethods().stream()
                                    .map(methodDeclaration -> Tuples.of(compilationUnit, classOrInterfaceDeclaration, methodDeclaration));
                        }
                )
                .filter(tuple3 ->
                        tuple3.getT2().getAnnotations().stream()
                                .map(annotationExpr -> processorManager.getQualifiedName(annotationExpr))
                                .anyMatch(name -> name.equals(annotationName)) &&
                                tuple3.getT3().isAnnotationPresent(annotationClass)
                )
                .sorted(Comparator.comparing(tuple3 -> getPriorityFromInterceptor(tuple3.getT2()), Comparator.reverseOrder()))
                .collect(Collectors.toList());
    }

    private int getPriorityFromInterceptor(ClassOrInterfaceDeclaration classOrInterfaceDeclaration) {
        return classOrInterfaceDeclaration.getAnnotationByClass(Priority.class)
                .flatMap(processorManager::findAnnotationValue)
                .map(expression -> expression.asIntegerLiteralExpr().asNumber().intValue())
                .orElse(Integer.MAX_VALUE);
    }
}
