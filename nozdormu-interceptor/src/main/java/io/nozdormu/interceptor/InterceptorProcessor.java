package io.nozdormu.interceptor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.google.auto.service.AutoService;
import com.google.common.base.CaseFormat;
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
public class InterceptorProcessor implements ComponentProxyProcessor {

    private ProcessorManager processorManager;
    private Map<String, Set<String>> interceptorAnnotationMap = new HashMap<>();

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
                            return getAspectAnnotationNameList(compilationUnit).stream().map(annotationName -> Tuples.of(annotationName, processorManager.getQualifiedName(classOrInterfaceDeclaration)));
                        }
                )
                .collect(Collectors.groupingBy(Tuple2::getT1, Collectors.mapping(Tuple2::getT2, Collectors.toSet())))
                .forEach((key, value) -> {
                            processorManager.getResource("META-INF/interceptor/" + key)
                                    .ifPresent(fileObject -> {
                                                try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(fileObject.openInputStream()))) {
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
                            Logger.info("annotation interceptor resource build success: {}", key);
                        }
                );
    }

    @Override
    public void processComponentProxy(CompilationUnit componentCompilationUnit, ClassOrInterfaceDeclaration componentClassDeclaration, CompilationUnit componentProxyCompilationUnit, ClassOrInterfaceDeclaration componentProxyClassDeclaration) {
        this.interceptorAnnotationMap = processorManager.getCompilationUnitListWithAnnotationClass(Interceptor.class).stream()
                .flatMap(compilationUnit -> {
                            ClassOrInterfaceDeclaration classOrInterfaceDeclaration = processorManager.getPublicClassOrInterfaceDeclarationOrError(compilationUnit);
                            return getAspectAnnotationNameList(compilationUnit).stream().map(annotationName -> Tuples.of(annotationName, processorManager.getQualifiedName(classOrInterfaceDeclaration)));
                        }
                )
                .collect(Collectors.groupingBy(Tuple2::getT1, Collectors.mapping(Tuple2::getT2, Collectors.toSet())));

        registerMetaInf();
        Set<String> annotationNameList = this.interceptorAnnotationMap.values().stream()
                .flatMap(Collection::stream)
                .map(className -> processorManager.getCompilationUnitOrError(className))
                .flatMap(compilationUnit -> getAspectAnnotationNameList(compilationUnit).stream())
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
                                        .addImport(InvocationContext.class)
                                        .addImport(InvocationContextProxy.class)
                                        .addImport(BeanContext.class);

                                MethodDeclaration overrideMethodDeclaration = componentProxyClassDeclaration.addMethod(methodDeclaration.getNameAsString())
                                        .setModifiers(methodDeclaration.getModifiers())
                                        .setParameters(methodDeclaration.getParameters())
                                        .setType(methodDeclaration.getType())
                                        .addAnnotation(Override.class);

                                String proxyMethodName = methodDeclaration.getNameAsString() +
                                        "_" +
                                        methodDeclaration.getParameters().stream()
                                                .map(parameter -> parameter.getType().isClassOrInterfaceType() ? parameter.getType().asClassOrInterfaceType().getNameAsString() : parameter.getType().asString())
                                                .collect(Collectors.joining("_")) +
                                        "_Proxy";

                                MethodDeclaration proxyMethodDeclaration = componentProxyClassDeclaration.addMethod(proxyMethodName)
                                        .setModifiers(methodDeclaration.getModifiers())
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

                                String nextContextName = null;
                                Tuple3<CompilationUnit, ClassOrInterfaceDeclaration, MethodDeclaration> nextTuple3 = null;

                                List<AnnotationExpr> annotationExprList = methodDeclaration.getAnnotations().stream()
                                        .filter(annotationExpr -> annotationNameList.contains(processorManager.getQualifiedName(annotationExpr)))
                                        .collect(Collectors.toList());

                                for (AnnotationExpr annotationExpr : annotationExprList) {
                                    String annotationName = processorManager.getQualifiedName(annotationExpr);
                                    for (Tuple3<CompilationUnit, ClassOrInterfaceDeclaration, MethodDeclaration> tuple3 : getInterceptorMethodList(annotationName, AroundInvoke.class)) {
                                        componentProxyCompilationUnit
                                                .addImport(processorManager.getQualifiedName(tuple3.getT2()))
                                                .addImport(annotationName);
                                        CompilationUnit invokeCompilationUnit = tuple3.getT1();
                                        ClassOrInterfaceDeclaration invokeClassOrInterfaceDeclaration = tuple3.getT2();
                                        MethodDeclaration invokeMethodDeclaration = tuple3.getT3();

                                        String interceptorFieldName = CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, annotationExpr.getNameAsString() + "_" + invokeClassOrInterfaceDeclaration.getNameAsString() + "_" + invokeMethodDeclaration.getNameAsString());
                                        String contextName = interceptorFieldName + "Context";

                                        Expression createContextExpr = new MethodCallExpr("setOwner")
                                                .addArgument(new ClassExpr().setType(annotationExpr.getName().getIdentifier()))
                                                .setScope(
                                                        new MethodCallExpr("setTarget")
                                                                .addArgument(methodDeclaration.isStatic() ? new ClassExpr().setType(componentProxyClassDeclaration.getNameAsString()) : new ThisExpr())
                                                                .setScope(new ObjectCreationExpr().setType(InvocationContextProxy.class))
                                                );

                                        MethodCallExpr proxyMethodCallExpr;
                                        if (nextContextName == null) {
                                            if (methodDeclaration.getType().isVoidType()) {
                                                proxyMethodCallExpr = new MethodCallExpr()
                                                        .setName("setConsumer")
                                                        .addArgument(
                                                                new MethodReferenceExpr()
                                                                        .setIdentifier(proxyMethodName)
                                                                        .setScope(methodDeclaration.isStatic() ? new NameExpr(componentProxyClassDeclaration.getNameAsString()) : new ThisExpr())
                                                        );
                                            } else {
                                                proxyMethodCallExpr = new MethodCallExpr()
                                                        .setName("setFunction")
                                                        .addArgument(
                                                                new MethodReferenceExpr()
                                                                        .setIdentifier(proxyMethodName)
                                                                        .setScope(methodDeclaration.isStatic() ? new NameExpr(componentProxyClassDeclaration.getNameAsString()) : new ThisExpr())
                                                        )
                                                        .setScope(createContextExpr);
                                            }
                                        } else {
                                            proxyMethodCallExpr = new MethodCallExpr()
                                                    .setName("setNextInvocationContext")
                                                    .addArgument(new NameExpr(nextContextName))
                                                    .setScope(
                                                            new MethodCallExpr()
                                                                    .setName("setNextProceed")
                                                                    .addArgument(
                                                                            new MethodReferenceExpr()
                                                                                    .setIdentifier(nextTuple3.getT3().getNameAsString())
                                                                                    .setScope(
                                                                                            new MethodCallExpr("get")
                                                                                                    .setScope(
                                                                                                            new MethodCallExpr("getProvider")
                                                                                                                    .setScope(new NameExpr("BeanContext"))
                                                                                                                    .addArgument(new ClassExpr().setType(nextTuple3.getT2().getNameAsString()))
                                                                                                    )
                                                                                    )
                                                                    )
                                                                    .setScope(createContextExpr)
                                                    );
                                        }

                                        VariableDeclarator variableDeclarator = new VariableDeclarator()
                                                .setType(InvocationContext.class)
                                                .setName(contextName);
                                        VariableDeclarationExpr variableDeclarationExpr = new VariableDeclarationExpr().addVariable(variableDeclarator);

                                        if (annotationExpr.isNormalAnnotationExpr()) {
                                            Expression addOwnerValueExpr = annotationExpr.asNormalAnnotationExpr().getPairs().stream()
                                                    .reduce(new MemberValuePair().setValue(proxyMethodCallExpr), (left, right) ->
                                                            new MemberValuePair().setValue(
                                                                    new MethodCallExpr("addOwnerValue")
                                                                            .addArgument(new StringLiteralExpr(right.getNameAsString()))
                                                                            .addArgument(right.getValue())
                                                                            .setScope(left.getValue()))
                                                    )
                                                    .getValue();
                                            variableDeclarator.setInitializer(addOwnerValueExpr);
                                        } else {
                                            variableDeclarator.setInitializer(proxyMethodCallExpr);
                                        }
                                        overrideMethodDeclaration.getBody().orElseGet(overrideMethodDeclaration::createBody).addStatement(variableDeclarationExpr);

                                        nextContextName = contextName;
                                        nextTuple3 = tuple3;

                                        Logger.info("{}.{} add interceptor {}.{} for annotation {}",
                                                processorManager.getQualifiedName(componentClassDeclaration),
                                                methodDeclaration.getNameAsString(),
                                                processorManager.getQualifiedName(invokeClassOrInterfaceDeclaration),
                                                invokeMethodDeclaration.getNameAsString(),
                                                annotationName
                                        );
                                    }
                                }

                                processorManager.importAllClassOrInterfaceType(componentProxyClassDeclaration, componentClassDeclaration);
                                BlockStmt blockStmt = overrideMethodDeclaration.getBody().orElseGet(overrideMethodDeclaration::createBody);
                                blockStmt.getStatements().getLast()
                                        .ifPresent(statement -> {
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

                                                    blockStmt.replace(
                                                            statement,
                                                            new ExpressionStmt(
                                                                    new MethodCallExpr()
                                                                            .setName("setMethod")
                                                                            .addArgument(methodName)
                                                                            .addArgument(methodParameterCount)
                                                                            .addArgument(methodParameterTypeNames)
                                                                            .setScope(
                                                                                    methodDeclaration.getParameters().stream()
                                                                                            .map(parameter -> (Expression) parameter.getNameAsExpression())
                                                                                            .reduce(statement.asExpressionStmt().getExpression(), (left, right) ->
                                                                                                    new MethodCallExpr("addParameterValue")
                                                                                                            .addArgument(new StringLiteralExpr(right.asNameExpr().getNameAsString()))
                                                                                                            .addArgument(right)
                                                                                                            .setScope(left)
                                                                                            )
                                                                            )
                                                            )
                                                    );
                                                }
                                        );

                                assert nextTuple3 != null;
                                blockStmt.addStatement(
                                        new ReturnStmt(
                                                new CastExpr()
                                                        .setType(methodDeclaration.getType())
                                                        .setExpression(
                                                                new MethodCallExpr()
                                                                        .setName(nextTuple3.getT3().getNameAsString())
                                                                        .addArgument(new NameExpr(nextContextName))
                                                                        .setScope(
                                                                                new MethodCallExpr("get")
                                                                                        .setScope(
                                                                                                new MethodCallExpr("getProvider")
                                                                                                        .setScope(new NameExpr("BeanContext"))
                                                                                                        .addArgument(new ClassExpr().setType(nextTuple3.getT2().getNameAsString()))
                                                                                        )
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
                                        .addImport(InvocationContext.class)
                                        .addImport(InvocationContextProxy.class)
                                        .addImport(BeanContext.class);

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

                                String nextContextName = null;
                                Tuple3<CompilationUnit, ClassOrInterfaceDeclaration, MethodDeclaration> nextTuple3 = null;

                                List<AnnotationExpr> annotationExprList = constructorDeclaration.getAnnotations().stream()
                                        .filter(annotationExpr -> annotationNameList.contains(processorManager.getQualifiedName(annotationExpr)))
                                        .collect(Collectors.toList());

                                for (AnnotationExpr annotationExpr : annotationExprList) {
                                    String annotationName = processorManager.getQualifiedName(annotationExpr);
                                    for (Tuple3<CompilationUnit, ClassOrInterfaceDeclaration, MethodDeclaration> tuple3 : getInterceptorMethodList(annotationName, AroundConstruct.class)) {
                                        componentProxyCompilationUnit
                                                .addImport(processorManager.getQualifiedName(tuple3.getT2()))
                                                .addImport(annotationName);
                                        CompilationUnit invokeCompilationUnit = tuple3.getT1();
                                        ClassOrInterfaceDeclaration invokeClassOrInterfaceDeclaration = tuple3.getT2();
                                        MethodDeclaration invokeMethodDeclaration = tuple3.getT3();

                                        String interceptorFieldName = CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, annotationExpr.getNameAsString() + "_" + invokeClassOrInterfaceDeclaration.getNameAsString() + "_" + invokeMethodDeclaration.getNameAsString());
                                        String contextName = interceptorFieldName + "Context";

                                        Expression createContextExpr = new MethodCallExpr()
                                                .setName("setOwner")
                                                .addArgument(new ClassExpr().setType(annotationExpr.getName().getIdentifier()))
                                                .setScope(
                                                        new MethodCallExpr()
                                                                .setName("setTarget")
                                                                .addArgument(new ClassExpr().setType(componentProxyClassDeclaration.getNameAsString()))
                                                                .setScope(new ObjectCreationExpr().setType(InvocationContextProxy.class))
                                                );

                                        MethodCallExpr proxyMethodCallExpr;
                                        if (nextContextName == null) {
                                            proxyMethodCallExpr = new MethodCallExpr()
                                                    .setName("setFunction")
                                                    .addArgument(
                                                            new MethodReferenceExpr()
                                                                    .setIdentifier("create" + componentProxyClassDeclaration.getNameAsString())
                                                                    .setScope(new NameExpr(componentProxyClassDeclaration.getNameAsString()))
                                                    )
                                                    .setScope(createContextExpr);
                                        } else {
                                            proxyMethodCallExpr = new MethodCallExpr()
                                                    .setName("setNextInvocationContext")
                                                    .addArgument(new NameExpr(nextContextName))
                                                    .setScope(
                                                            new MethodCallExpr()
                                                                    .setName("setNextProceed")
                                                                    .addArgument(
                                                                            new MethodReferenceExpr()
                                                                                    .setIdentifier(nextTuple3.getT3().getNameAsString())
                                                                                    .setScope(
                                                                                            new MethodCallExpr("get")
                                                                                                    .setScope(
                                                                                                            new MethodCallExpr("getProvider")
                                                                                                                    .setScope(new NameExpr("BeanContext"))
                                                                                                                    .addArgument(new ClassExpr().setType(nextTuple3.getT2().getNameAsString()))
                                                                                                    )
                                                                                    )
                                                                    )
                                                                    .setScope(createContextExpr)
                                                    );
                                        }

                                        VariableDeclarator variableDeclarator = new VariableDeclarator()
                                                .setType(InvocationContext.class)
                                                .setName(contextName);
                                        VariableDeclarationExpr variableDeclarationExpr = new VariableDeclarationExpr().addVariable(variableDeclarator);

                                        if (annotationExpr.isNormalAnnotationExpr()) {
                                            Expression addOwnerValueExpr = annotationExpr.asNormalAnnotationExpr().getPairs().stream()
                                                    .reduce(new MemberValuePair().setValue(proxyMethodCallExpr), (left, right) ->
                                                            new MemberValuePair().setValue(
                                                                    new MethodCallExpr("addOwnerValue")
                                                                            .addArgument(new StringLiteralExpr(right.getNameAsString()))
                                                                            .addArgument(right.getValue())
                                                                            .setScope(left.getValue()))
                                                    )
                                                    .getValue();
                                            variableDeclarator.setInitializer(addOwnerValueExpr);
                                        } else {
                                            variableDeclarator.setInitializer(proxyMethodCallExpr);
                                        }
                                        creatorMethod.getBody().orElseGet(creatorMethod::createBody).addStatement(variableDeclarationExpr);

                                        nextContextName = contextName;
                                        nextTuple3 = tuple3;

                                        Logger.info("{}.{} add interceptor {}.{} for annotation {}",
                                                processorManager.getQualifiedName(componentClassDeclaration),
                                                constructorDeclaration.getNameAsString(),
                                                processorManager.getQualifiedName(invokeClassOrInterfaceDeclaration),
                                                invokeMethodDeclaration.getNameAsString(),
                                                annotationName
                                        );
                                    }
                                }

                                processorManager.importAllClassOrInterfaceType(componentProxyClassDeclaration, componentClassDeclaration);
                                BlockStmt blockStmt = creatorMethod.getBody().orElseGet(invocationCreatorMethod::createBody);
                                blockStmt.getStatements().getLast()
                                        .ifPresent(statement -> {
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

                                                    blockStmt.replace(
                                                            statement,
                                                            new ExpressionStmt(
                                                                    new MethodCallExpr()
                                                                            .setName("setConstructor")
                                                                            .addArgument(constructorParameterCount)
                                                                            .addArgument(constructorParameterTypeNames)
                                                                            .setScope(
                                                                                    constructorDeclaration.getParameters().stream()
                                                                                            .map(parameter -> (Expression) parameter.getNameAsExpression())
                                                                                            .reduce(statement.asExpressionStmt().getExpression(), (left, right) ->
                                                                                                    new MethodCallExpr("addParameterValue")
                                                                                                            .addArgument(new StringLiteralExpr(right.asNameExpr().getNameAsString()))
                                                                                                            .addArgument(right)
                                                                                                            .setScope(left)
                                                                                            )
                                                                            )
                                                            )
                                                    );
                                                }
                                        );

                                assert nextTuple3 != null;
                                blockStmt.addStatement(
                                        new ReturnStmt(
                                                new CastExpr()
                                                        .setType(componentProxyClassDeclaration.getNameAsString())
                                                        .setExpression(
                                                                new MethodCallExpr()
                                                                        .setName(nextTuple3.getT3().getNameAsString())
                                                                        .addArgument(new NameExpr(nextContextName))
                                                                        .setScope(
                                                                                new MethodCallExpr("get")
                                                                                        .setScope(
                                                                                                new MethodCallExpr("getProvider")
                                                                                                        .setScope(new NameExpr("BeanContext"))
                                                                                                        .addArgument(new ClassExpr().setType(nextTuple3.getT2().getNameAsString()))
                                                                                        )
                                                                        )
                                                        )
                                        )
                                );
                            }
                        }
                );
    }


    private List<String> getAspectAnnotationNameList(CompilationUnit compilationUnit) {
        ClassOrInterfaceDeclaration classOrInterfaceDeclaration = processorManager.getPublicClassOrInterfaceDeclarationOrError(compilationUnit);

        List<String> annotationExprList = classOrInterfaceDeclaration.getAnnotations().stream()
                .filter(annotationExpr -> {
                            CompilationUnit annotationCompilationUnit = processorManager.getCompilationUnitOrError(annotationExpr);
                            AnnotationDeclaration annotationDeclaration = processorManager.getPublicAnnotationDeclarationOrError(annotationCompilationUnit);
                            return annotationDeclaration.getAnnotations().stream()
                                    .anyMatch(subAnnotationExpr -> processorManager.getQualifiedName(subAnnotationExpr).equals(InterceptorBinding.class.getName()));
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
            Iterator<URL> urlIterator = Objects.requireNonNull(InterceptorProcessor.class.getClassLoader().getResources("META-INF/interceptor")).asIterator();
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
