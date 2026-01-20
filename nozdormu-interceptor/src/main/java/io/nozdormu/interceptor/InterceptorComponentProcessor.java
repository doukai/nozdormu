package io.nozdormu.interceptor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.UnknownType;
import com.google.auto.service.AutoService;
import io.nozdormu.common.ProcessorManager;
import io.nozdormu.spi.context.BeanContext;
import io.nozdormu.inject.processor.ComponentProxyProcessor;
import jakarta.enterprise.inject.Produces;
import jakarta.interceptor.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@AutoService(ComponentProxyProcessor.class)
public class InterceptorComponentProcessor implements ComponentProxyProcessor {

    private static final Logger logger = LoggerFactory.getLogger(InterceptorComponentProcessor.class);

    private ProcessorManager processorManager;

    @Override
    public void init(ProcessorManager processorManager) {
        this.processorManager = processorManager;
    }

    @Override
    public void processComponentProxy(CompilationUnit componentCompilationUnit, ClassOrInterfaceDeclaration componentClassDeclaration, CompilationUnit componentProxyCompilationUnit, ClassOrInterfaceDeclaration componentProxyClassDeclaration) {
        logger.info("{} interceptor component build start", componentClassDeclaration.getFullyQualifiedName().orElseGet(componentClassDeclaration::getNameAsString));
        buildMethod(componentClassDeclaration, componentProxyCompilationUnit, componentProxyClassDeclaration);
        buildConstructor(componentClassDeclaration, componentProxyCompilationUnit, componentProxyClassDeclaration);
        logger.info("{} interceptor component build success", componentClassDeclaration.getFullyQualifiedName().orElseGet(componentClassDeclaration::getNameAsString));
    }

    private void buildMethod(ClassOrInterfaceDeclaration componentClassDeclaration, CompilationUnit componentProxyCompilationUnit, ClassOrInterfaceDeclaration componentProxyClassDeclaration) {
        componentClassDeclaration.getMethods()
                .forEach(methodDeclaration -> {
                    List<AnnotationExpr> annotationExprList = methodDeclaration.getAnnotations().stream()
                            .filter(annotationExpr ->
                                    processorManager.getCompilationUnit(annotationExpr)
                                            .flatMap(compilationUnit -> processorManager.getPublicAnnotationDeclaration(compilationUnit)).stream()
                                            .flatMap(annotationDeclaration -> annotationDeclaration.getAnnotations().stream())
                                            .anyMatch(subAnnotationExpr -> processorManager.getQualifiedName(subAnnotationExpr).equals(InterceptorBinding.class.getName()))
                            )
                            .collect(Collectors.toList());

                    if (!annotationExprList.isEmpty()) {
                        componentProxyCompilationUnit
                                .addImport(InvokeInterceptor.class)
                                .addImport(InvocationContext.class)
                                .addImport(InvocationContextProxy.class)
                                .addImport(BeanContext.class)
                                .addImport(Optional.class)
                                .addImport(Map.class)
                                .addImport(List.class);

                        String proxyMethodName = methodDeclaration.getNameAsString() +
                                "_" +
                                methodDeclaration.getParameters().stream()
                                        .map(parameter -> parameter.getType().isClassOrInterfaceType() ? parameter.getType().asClassOrInterfaceType().getNameAsString() : parameter.getType().asString())
                                        .collect(Collectors.joining("_")) +
                                "_Proxy";

                        componentProxyClassDeclaration.addMember(
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
                                                        .setName(proxyMethodName + "InvokeInterceptorList")
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

                        MethodDeclaration overrideMethodDeclaration = componentProxyClassDeclaration.addMethod(methodDeclaration.getNameAsString())
                                .setModifiers(methodDeclaration.getModifiers().stream().collect(Collectors.toCollection(NodeList::new)))
                                .setParameters(methodDeclaration.getParameters().stream().collect(Collectors.toCollection(NodeList::new)))
                                .setType(methodDeclaration.getType())
                                .addAnnotation(Override.class);

                        methodDeclaration.getTypeParameters()
                                .forEach(overrideMethodDeclaration::addTypeParameter);

                        MethodDeclaration proxyMethodDeclaration = componentProxyClassDeclaration.addMethod(proxyMethodName)
                                .setModifiers(methodDeclaration.getModifiers().stream().collect(Collectors.toCollection(NodeList::new)))
                                .addParameter(InvocationContext.class, "invocationContext");

                        methodDeclaration.getTypeParameters()
                                .forEach(proxyMethodDeclaration::addTypeParameter);

                        VariableDeclarationExpr invocationContextProxyVariable = new VariableDeclarationExpr()
                                .addVariable(
                                        new VariableDeclarator()
                                                .setType(InvocationContextProxy.class)
                                                .setName("invocationContextProxy")
                                                .setInitializer(new CastExpr()
                                                        .setType(InvocationContextProxy.class)
                                                        .setExpression(new NameExpr("invocationContext"))
                                                )
                                );

                        MethodCallExpr superMethodCallExpr = new MethodCallExpr()
                                .setName(methodDeclaration.getName())
                                .setArguments(
                                        methodDeclaration.getParameters().stream()
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
                                    .setType(methodDeclaration.getType())
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
                                                                })
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

                        body.addStatement(
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
                                                                                                                        .setType(methodDeclaration.getType())
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
                                                                                                                                                new MethodCallExpr()
                                                                                                                                                        .setName("setMethod")
                                                                                                                                                        .addArgument(methodName)
                                                                                                                                                        .addArgument(methodParameterCount)
                                                                                                                                                        .addArgument(methodParameterTypeNames)
                                                                                                                                                        .setScope(
                                                                                                                                                                methodDeclaration.getParameters().stream()
                                                                                                                                                                        .map(parameter -> (Expression) parameter.getNameAsExpression())
                                                                                                                                                                        .reduce(setTarget, (left, right) ->
                                                                                                                                                                                new MethodCallExpr("addParameterValue")
                                                                                                                                                                                        .addArgument(new StringLiteralExpr(right.asNameExpr().getNameAsString()))
                                                                                                                                                                                        .addArgument(right)
                                                                                                                                                                                        .setScope(left)
                                                                                                                                                                        )
                                                                                                                                                        )
                                                                                                                                        )
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
                                                                                                                                                                                                                        .setName("getContextProxy")
                                                                                                                                                                                                                        .setScope(new NameExpr("cur"))
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
                                                                                                                                                                                        .setScope(
                                                                                                                                                                                                new MethodCallExpr()
                                                                                                                                                                                                        .setName("getContextProxy")
                                                                                                                                                                                                        .setScope(new NameExpr("cur"))
                                                                                                                                                                                        )
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
                                                                                                                        .setScope(new NameExpr(proxyMethodName + "InvokeInterceptorList"))
                                                                                                        )
                                                                                        )
                                                                                        .setScope(new NameExpr("Optional"))
                                                                        )
                                                        )
                                        )
                        );
                    }
                });
    }

    private void buildConstructor(ClassOrInterfaceDeclaration componentClassDeclaration, CompilationUnit componentProxyCompilationUnit, ClassOrInterfaceDeclaration componentProxyClassDeclaration) {
        componentClassDeclaration.getConstructors()
                .forEach(constructorDeclaration -> {
                    List<AnnotationExpr> annotationExprList = constructorDeclaration.getAnnotations().stream()
                            .filter(annotationExpr ->
                                    processorManager.getCompilationUnit(annotationExpr)
                                            .flatMap(compilationUnit -> processorManager.getPublicAnnotationDeclaration(compilationUnit)).stream()
                                            .flatMap(annotationDeclaration -> annotationDeclaration.getAnnotations().stream())
                                            .anyMatch(subAnnotationExpr -> processorManager.getQualifiedName(subAnnotationExpr).equals(InterceptorBinding.class.getName()))
                            )
                            .collect(Collectors.toList());

                    if (!annotationExprList.isEmpty()) {
                        componentProxyCompilationUnit
                                .addImport(ConstructInterceptor.class)
                                .addImport(InvocationContext.class)
                                .addImport(InvocationContextProxy.class)
                                .addImport(BeanContext.class)
                                .addImport(Optional.class)
                                .addImport(Map.class)
                                .addImport(List.class);

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
                                .addVariable(
                                        new VariableDeclarator()
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
                                                                })
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

                        body.addStatement(
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
                                                                                                                                                                                                                        .setName("getContextProxy")
                                                                                                                                                                                                                        .setScope(new NameExpr("cur"))
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
                                                                                                                                                                                .setScope(
                                                                                                                                                                                        new MethodCallExpr()
                                                                                                                                                                                                .setName("getContextProxy")
                                                                                                                                                                                                .setScope(new NameExpr("cur"))
                                                                                                                                                                                )
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
                });
    }
}
