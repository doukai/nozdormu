package io.nozdormu.async;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithStatements;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.UnknownType;
import com.github.javaparser.StaticJavaParser;
import com.google.auto.service.AutoService;
import io.nozdormu.common.ProcessorManager;
import io.nozdormu.inject.processor.ComponentProxyProcessor;
import io.nozdormu.spi.async.Async;
import io.nozdormu.spi.async.Asyncable;
import io.nozdormu.spi.error.InjectionProcessException;
import jakarta.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeMirror;

import static com.github.javaparser.ast.expr.BinaryExpr.Operator.EQUALS;
import static com.github.javaparser.ast.expr.BinaryExpr.Operator.NOT_EQUALS;
import static io.nozdormu.spi.async.Asyncable.ASYNC_METHOD_NAME_SUFFIX;
import static io.nozdormu.spi.error.InjectionProcessErrorType.VARIABLE_DECLARATOR_NOT_EXIST;

@AutoService(ComponentProxyProcessor.class)
public class AsyncComponentProcessor implements ComponentProxyProcessor {

    private static final Logger logger = LoggerFactory.getLogger(AsyncComponentProcessor.class);

    private ProcessorManager processorManager;

    @Override
    public void init(ProcessorManager processorManager) {
        this.processorManager = processorManager;
    }

    @Override
    public boolean processComponentProxy(CompilationUnit componentCompilationUnit, ClassOrInterfaceDeclaration componentClassDeclaration, CompilationUnit componentProxyCompilationUnit, ClassOrInterfaceDeclaration componentProxyClassDeclaration) {
        logger.info("{} async component build start", componentClassDeclaration.getFullyQualifiedName().orElseGet(componentClassDeclaration::getNameAsString));

        List<MethodDeclaration> asyncMethods = componentClassDeclaration.getMethods().stream()
                .filter(methodDeclaration -> methodDeclaration.isAnnotationPresent(Async.class))
                .collect(Collectors.toList());

        if (asyncMethods.isEmpty()) {
            return false;
        }

        asyncMethods.forEach(methodDeclaration ->
                methodDeclaration.getBody()
                        .ifPresent(methodBody -> {
                                    componentProxyCompilationUnit
                                            .addImport(Mono.class)
                                            .addImport(Flux.class)
                                            .addImport(RuntimeException.class);
                                    if (methodDeclaration.getType().isVoidType()) {
                                        componentProxyCompilationUnit.addImport(Void.class);
                                    }
                                    String asyncMethodName = Stream
                                            .concat(
                                                    Stream.of(methodDeclaration.getNameAsString() + ASYNC_METHOD_NAME_SUFFIX),
                                                    methodDeclaration.getParameters().stream()
                                                            .map(parameter -> {
                                                                        if (parameter.getType().isPrimitiveType()) {
                                                                            return parameter.getType().asPrimitiveType().toBoxedType().getNameAsString();
                                                                        } else if (parameter.getType().isClassOrInterfaceType()) {
                                                                            return parameter.getType().asClassOrInterfaceType().getNameAsString();
                                                                        } else {
                                                                            return parameter.getTypeAsString();
                                                                        }
                                                                    }
                                                            )
                                            )
                                            .collect(Collectors.joining("_"));

                                    MethodDeclaration asyncMethodDeclaration = new MethodDeclaration().setName(asyncMethodName)
                                            .setModifiers(methodDeclaration.getModifiers())
                                            .setParameters(methodDeclaration.getParameters())
                                            .setType(
                                                    new ClassOrInterfaceType().setName(Mono.class.getSimpleName())
                                                            .setTypeArguments(
                                                                    methodDeclaration.getType().isPrimitiveType() ?
                                                                            methodDeclaration.getType().asPrimitiveType().toBoxedType() :
                                                                            methodDeclaration.getType().isVoidType() ?
                                                                                    new ClassOrInterfaceType().setName(Void.class.getSimpleName()) :
                                                                                    methodDeclaration.getType()
                                                            )
                                            );
                                    componentProxyClassDeclaration.addMember(asyncMethodDeclaration);
                                    methodDeclaration.getTypeParameters().forEach(asyncMethodDeclaration::addTypeParameter);

                                    String defaultIfEmpty = methodDeclaration.getAnnotationByClass(Async.class)
                                            .filter((Expression::isNormalAnnotationExpr))
                                            .flatMap(annotationExpr ->
                                                    annotationExpr.asNormalAnnotationExpr().getPairs().stream()
                                                            .filter(memberValuePair -> memberValuePair.getNameAsString().equals("defaultIfEmpty"))
                                                            .findFirst()
                                            )
                                            .map(memberValuePair -> memberValuePair.getValue().asStringLiteralExpr().asString())
                                            .orElse(null);
                                    NodeList<Statement> statements = buildAsyncStatements(methodBody.getStatements(), defaultIfEmpty);
                                    if (methodDeclaration.getType().isVoidType()) {
                                        asyncMethodDeclaration.createBody().setStatements(statements);
                                    } else {
                                        asyncMethodDeclaration.createBody().setStatements(
                                                statements.stream()
                                                        .map(statement -> {
                                                                    if (statement.isReturnStmt()) {
                                                                        if (statement.asReturnStmt().getExpression().stream()
                                                                                .noneMatch(
                                                                                        expression ->
                                                                                                expression.isMethodCallExpr() &&
                                                                                                        expression.asMethodCallExpr().getNameAsString().equals("empty") &&
                                                                                                        expression.asMethodCallExpr().getScope().isPresent() &&
                                                                                                        expression.asMethodCallExpr().getScope().get().isNameExpr() &&
                                                                                                        expression.asMethodCallExpr().getScope().get().asNameExpr().getNameAsString().equals(Mono.class.getSimpleName())
                                                                                )
                                                                        ) {
                                                                            return statement.asReturnStmt().getExpression()
                                                                                    .map(expression ->
                                                                                            (Statement) new ReturnStmt(
                                                                                                    new MethodCallExpr("map")
                                                                                                            .addArgument(
                                                                                                                    new LambdaExpr()
                                                                                                                            .addParameter(new Parameter(new UnknownType(), "object"))
                                                                                                                            .setBody(new ExpressionStmt(new CastExpr().setType(methodDeclaration.getType().isPrimitiveType() ? methodDeclaration.getType().asPrimitiveType().toBoxedType() : methodDeclaration.getType()).setExpression(new NameExpr("object"))))
                                                                                                            )
                                                                                                            .setScope(expression)
                                                                                            )
                                                                                    )
                                                                                    .orElse(statement);
                                                                        }
                                                                    }
                                                                    return statement;
                                                                }
                                                        )
                                                        .collect(Collectors.toCollection(NodeList::new))
                                        );
                                    }
                                }
                        )
        );
        buildAsyncMethodDeclaration(componentClassDeclaration, asyncMethods).ifPresent(componentProxyClassDeclaration::addMember);

        logger.info("{} async component build success", componentClassDeclaration.getFullyQualifiedName().orElseGet(componentClassDeclaration::getNameAsString));
        return true;
    }

    protected NodeList<Statement> buildAsyncStatements(List<Statement> statementNodeList, String defaultIfEmpty) {
        NodeList<Statement> asyncStatements = new NodeList<>();
        for (int i = 0; i < statementNodeList.size(); i++) {
            Statement statement = statementNodeList.get(i);
            List<Statement> lastStatementList = statementNodeList.subList(i + 1, statementNodeList.size());
            boolean lastHasReturnOrThrow = hasReturnOrThrowStmt(lastStatementList);
            boolean lastHasAwait = hasAwait(lastStatementList);
            if (statement.isExpressionStmt() &&
                    statement.asExpressionStmt().getExpression().isMethodCallExpr() &&
                    statement.asExpressionStmt().getExpression().asMethodCallExpr().getNameAsString().equals("await") &&
                    (statement.asExpressionStmt().getExpression().asMethodCallExpr().getArgument(0).isMethodCallExpr() ||
                            statement.asExpressionStmt().getExpression().asMethodCallExpr().getArgument(0).isLambdaExpr() &&
                                    statement.asExpressionStmt().getExpression().asMethodCallExpr().getArgument(0).asLambdaExpr().getBody().isExpressionStmt() &&
                                    statement.asExpressionStmt().getExpression().asMethodCallExpr().getArgument(0).asLambdaExpr().getBody().asExpressionStmt().getExpression().isMethodCallExpr()
                    )
            ) {
                MethodCallExpr methodCallExpr = statement.asExpressionStmt().getExpression().asMethodCallExpr().getArgument(0).isMethodCallExpr() ?
                        statement.asExpressionStmt().getExpression().asMethodCallExpr().getArgument(0).asMethodCallExpr() :
                        statement.asExpressionStmt().getExpression().asMethodCallExpr().getArgument(0).asLambdaExpr().getBody().asExpressionStmt().getExpression().asMethodCallExpr();
                String methodDeclarationReturnTypeName = processorManager.resolveMethodDeclarationReturnTypeQualifiedName(methodCallExpr);
                if (methodCallExpr.getScope().isPresent() && processorManager.calculateType(methodCallExpr.getScope().get()).asReferenceType().getQualifiedName().equals(Provider.class.getCanonicalName()) ||
                        methodDeclarationReturnTypeName.equals(Mono.class.getCanonicalName())) {
                    if (lastStatementList.isEmpty()) {
                        Optional<NodeList<Statement>> parentReturnOrThrowStatementList = statement.getParentNode()
                                .flatMap(node -> getParentReturnOrThrowStatementList(node, defaultIfEmpty));
                        if (parentReturnOrThrowStatementList.isPresent()) {
                            methodCallExpr = new MethodCallExpr("then")
                                    .setScope(methodCallExpr)
                                    .addArgument(
                                            new MethodCallExpr("defer")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .setEnclosingParameters(true)
                                                                    .setBody(new BlockStmt(parentReturnOrThrowStatementList.get()))
                                                    )
                                                    .setScope(new NameExpr(Mono.class.getSimpleName()))
                                    );
                        }
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(methodCallExpr);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(methodCallExpr));
                        }
                    } else if (lastHasReturnOrThrow) {
                        MethodCallExpr then = new MethodCallExpr("then")
                                .addArgument(
                                        new MethodCallExpr("defer")
                                                .addArgument(
                                                        new LambdaExpr()
                                                                .setEnclosingParameters(true)
                                                                .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                                )
                                                .setScope(new NameExpr(Mono.class.getSimpleName()))
                                )
                                .setScope(methodCallExpr);
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(then);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(then));
                        }
                    } else if (lastHasAwait) {
                        MethodCallExpr then = new MethodCallExpr("then")
                                .addArgument(
                                        new MethodCallExpr("defer")
                                                .addArgument(
                                                        new LambdaExpr()
                                                                .setEnclosingParameters(true)
                                                                .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                                )
                                                .setScope(new NameExpr(Mono.class.getSimpleName()))
                                )
                                .setScope(methodCallExpr);
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(then);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(then));
                        }
                    } else {
                        MethodCallExpr thenEmpty = new MethodCallExpr("thenEmpty")
                                .addArgument(
                                        new MethodCallExpr("fromRunnable")
                                                .addArgument(
                                                        new LambdaExpr()
                                                                .setEnclosingParameters(true)
                                                                .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                                )
                                                .setScope(new NameExpr(Mono.class.getSimpleName()))
                                )
                                .setScope(methodCallExpr);
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(thenEmpty);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(thenEmpty));
                        }
                    }
                    break;
                } else if (methodDeclarationReturnTypeName.equals(Flux.class.getCanonicalName())) {
                    if (lastStatementList.isEmpty()) {
                        Optional<NodeList<Statement>> parentReturnOrThrowStatementList = statement.getParentNode()
                                .flatMap(node -> getParentReturnOrThrowStatementList(node, defaultIfEmpty));
                        if (parentReturnOrThrowStatementList.isPresent()) {
                            methodCallExpr = new MethodCallExpr("then")
                                    .setScope(
                                            new MethodCallExpr("collectList")
                                                    .setScope(methodCallExpr)
                                    )
                                    .addArgument(
                                            new MethodCallExpr("defer")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .setEnclosingParameters(true)
                                                                    .setBody(new BlockStmt(parentReturnOrThrowStatementList.get()))
                                                    )
                                                    .setScope(new NameExpr(Mono.class.getSimpleName()))
                                    );
                        }
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(methodCallExpr);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(methodCallExpr));
                        }
                    } else if (lastHasReturnOrThrow) {
                        MethodCallExpr then = new MethodCallExpr("then")
                                .addArgument(
                                        new MethodCallExpr("defer")
                                                .addArgument(
                                                        new LambdaExpr()
                                                                .setEnclosingParameters(true)
                                                                .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                                )
                                                .setScope(new NameExpr(Mono.class.getSimpleName()))
                                )
                                .setScope(
                                        new MethodCallExpr("collectList")
                                                .setScope(methodCallExpr)
                                );
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(then);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(then));
                        }
                    } else if (lastHasAwait) {
                        MethodCallExpr then = new MethodCallExpr("then")
                                .addArgument(
                                        new MethodCallExpr("defer")
                                                .addArgument(
                                                        new LambdaExpr()
                                                                .setEnclosingParameters(true)
                                                                .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                                )
                                                .setScope(new NameExpr(Mono.class.getSimpleName()))
                                )
                                .setScope(
                                        new MethodCallExpr("collectList")
                                                .setScope(methodCallExpr)
                                );
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(then);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(then));
                        }
                    } else {
                        MethodCallExpr then = new MethodCallExpr("then")
                                .addArgument(
                                        new MethodCallExpr("fromRunnable")
                                                .addArgument(
                                                        new LambdaExpr()
                                                                .setEnclosingParameters(true)
                                                                .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                                )
                                                .setScope(new NameExpr(Mono.class.getSimpleName()))
                                )
                                .setScope(
                                        new MethodCallExpr("collectList")
                                                .setScope(methodCallExpr)
                                );
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(then);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(then));
                        }
                    }
                    break;
                } else {
                    String asyncMethodName = Stream
                            .concat(
                                    Stream.of(methodCallExpr.getNameAsString() + ASYNC_METHOD_NAME_SUFFIX),
                                    processorManager.resolveMethodDeclarationParameterTypeNames(methodCallExpr)
                            )
                            .collect(Collectors.joining("_"));

                    MethodCallExpr asyncMethodCallExpr = new MethodCallExpr("async")
                            .setArguments(
                                    Stream
                                            .concat(
                                                    Stream.of(new StringLiteralExpr(asyncMethodName)),
                                                    methodCallExpr.getArguments().stream()
                                            )
                                            .collect(Collectors.toCollection(NodeList::new))
                            );

                    methodCallExpr.getScope().ifPresent(asyncMethodCallExpr::setScope);

                    if (lastStatementList.isEmpty()) {
                        Optional<NodeList<Statement>> parentReturnOrThrowStatementList = statement.getParentNode()
                                .flatMap(node -> getParentReturnOrThrowStatementList(node, defaultIfEmpty));
                        if (parentReturnOrThrowStatementList.isPresent()) {
                            asyncMethodCallExpr = new MethodCallExpr("then")
                                    .setScope(asyncMethodCallExpr)
                                    .addArgument(
                                            new MethodCallExpr("defer")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .setEnclosingParameters(true)
                                                                    .setBody(new BlockStmt(parentReturnOrThrowStatementList.get()))
                                                    )
                                                    .setScope(new NameExpr(Mono.class.getSimpleName()))
                                    );
                        }
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(asyncMethodCallExpr);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(asyncMethodCallExpr));
                        }
                    } else if (lastHasReturnOrThrow) {
                        MethodCallExpr then = new MethodCallExpr("then")
                                .addArgument(
                                        new MethodCallExpr("defer")
                                                .addArgument(
                                                        new LambdaExpr()
                                                                .setEnclosingParameters(true)
                                                                .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                                )
                                                .setScope(new NameExpr(Mono.class.getSimpleName()))
                                )
                                .setScope(asyncMethodCallExpr);
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(then);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(then));
                        }
                    } else if (lastHasAwait) {
                        MethodCallExpr then = new MethodCallExpr("then")
                                .addArgument(
                                        new MethodCallExpr("defer")
                                                .addArgument(
                                                        new LambdaExpr()
                                                                .setEnclosingParameters(true)
                                                                .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                                )
                                                .setScope(new NameExpr(Mono.class.getSimpleName()))
                                )
                                .setScope(asyncMethodCallExpr);
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(then);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(then));
                        }
                    } else {
                        MethodCallExpr thenEmpty = new MethodCallExpr("thenEmpty")
                                .addArgument(
                                        new MethodCallExpr("fromRunnable")
                                                .addArgument(
                                                        new LambdaExpr()
                                                                .setEnclosingParameters(true)
                                                                .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                                )
                                                .setScope(new NameExpr(Mono.class.getSimpleName()))
                                )
                                .setScope(asyncMethodCallExpr);
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(thenEmpty);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(thenEmpty));
                        }
                    }
                    break;
                }
            } else if (statement.isExpressionStmt() &&
                    (statement.asExpressionStmt().getExpression().isVariableDeclarationExpr() &&
                            statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariables().size() == 1 &&
                            statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0).getInitializer().isPresent() &&
                            statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0).getInitializer().get().isMethodCallExpr() &&
                            statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0).getInitializer().get().asMethodCallExpr().getNameAsString().equals("await") &&
                            statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0).getInitializer().get().asMethodCallExpr().getArgument(0).isMethodCallExpr() ||
                            statement.asExpressionStmt().getExpression().isAssignExpr() &&
                                    statement.asExpressionStmt().getExpression().asAssignExpr().getValue().isMethodCallExpr() &&
                                    statement.asExpressionStmt().getExpression().asAssignExpr().getValue().asMethodCallExpr().getNameAsString().equals("await") &&
                                    statement.asExpressionStmt().getExpression().asAssignExpr().getValue().asMethodCallExpr().getArgument(0).isMethodCallExpr())
            ) {
                VariableDeclarator variableDeclarator;
                MethodCallExpr methodCallExpr;
                if (statement.asExpressionStmt().getExpression().isAssignExpr()) {
                    variableDeclarator = processorManager.getDeclaratorVariableDeclarator(statement.asExpressionStmt().getExpression().asAssignExpr().getTarget().asNameExpr())
                            .orElseThrow(() -> new InjectionProcessException(VARIABLE_DECLARATOR_NOT_EXIST.bind(statement)));
                    methodCallExpr = statement.asExpressionStmt().getExpression().asAssignExpr().getValue().asMethodCallExpr().getArgument(0).asMethodCallExpr();
                } else {
                    variableDeclarator = statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0);
                    methodCallExpr = variableDeclarator.getInitializer()
                            .map(expression -> expression.asMethodCallExpr().getArgument(0).asMethodCallExpr())
                            .orElseThrow(() -> new RuntimeException("incorrect await method definition: " + variableDeclarator));
                }
                boolean hasCheckAwaitIsNull = hasCheckAwaitIsNull(variableDeclarator.getNameAsString(), lastStatementList);
                String methodDeclarationReturnTypeName = processorManager.resolveMethodDeclarationReturnTypeQualifiedName(methodCallExpr);
                String methodDeclarationReturnDescribe = processorManager.resolveMethodDeclarationReturnTypeDescribe(methodCallExpr);
                if (methodCallExpr.getScope().isPresent() && processorManager.calculateType(methodCallExpr.getScope().get()).asReferenceType().getQualifiedName().equals(Provider.class.getCanonicalName()) ||
                        methodDeclarationReturnTypeName.equals(Mono.class.getCanonicalName())) {
                    if (lastHasReturnOrThrow) {
                        MethodCallExpr flatMap;
                        if (hasCheckAwaitIsNull) {
                            flatMap = new MethodCallExpr("switchIfEmpty")
                                    .addArgument(
                                            new MethodCallExpr("defer")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .setEnclosingParameters(true)
                                                                    .setBody(new BlockStmt(buildAsyncStatements(getAwaitIsNullStatementList(variableDeclarator.getNameAsString(), lastStatementList), defaultIfEmpty)))
                                                    )
                                                    .setScope(new NameExpr(Mono.class.getSimpleName()))
                                    )
                                    .setScope(
                                            new MethodCallExpr("flatMap")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                                    .setBody(new BlockStmt(buildAsyncStatements(getAwaitIsNotNullStatementList(variableDeclarator.getNameAsString(), lastStatementList), defaultIfEmpty)))
                                                    )
                                                    .setScope(methodCallExpr)
                                    );
                        } else {
                            flatMap = new MethodCallExpr("flatMap")
                                    .addArgument(
                                            new LambdaExpr()
                                                    .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                    .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                    )
                                    .setScope(methodCallExpr);
                        }
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(flatMap);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(flatMap));
                        }
                    } else if (lastHasAwait) {
                        MethodCallExpr flatMap;
                        if (hasCheckAwaitIsNull) {
                            flatMap = new MethodCallExpr("switchIfEmpty")
                                    .addArgument(
                                            new MethodCallExpr("defer")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .setEnclosingParameters(true)
                                                                    .setBody(new BlockStmt(buildAsyncStatements(getAwaitIsNullStatementList(variableDeclarator.getNameAsString(), lastStatementList), defaultIfEmpty)))
                                                    )
                                                    .setScope(new NameExpr(Mono.class.getSimpleName()))
                                    )
                                    .setScope(
                                            new MethodCallExpr("flatMap")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                                    .setBody(new BlockStmt(buildAsyncStatements(getAwaitIsNotNullStatementList(variableDeclarator.getNameAsString(), lastStatementList), defaultIfEmpty)))
                                                    )
                                                    .setScope(methodCallExpr)
                                    );
                        } else {
                            flatMap = new MethodCallExpr("flatMap")
                                    .addArgument(
                                            new LambdaExpr()
                                                    .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                    .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                    )
                                    .setScope(methodCallExpr);
                        }
                        Optional<NodeList<Statement>> parentReturnOrThrowStatementList = statement.getParentNode()
                                .flatMap(node -> getParentReturnOrThrowStatementList(node, defaultIfEmpty));
                        if (parentReturnOrThrowStatementList.isPresent()) {
                            flatMap = new MethodCallExpr("then")
                                    .setScope(flatMap)
                                    .addArgument(
                                            new MethodCallExpr("defer")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .setEnclosingParameters(true)
                                                                    .setBody(new BlockStmt(parentReturnOrThrowStatementList.get()))
                                                    )
                                                    .setScope(new NameExpr(Mono.class.getSimpleName()))
                                    );
                        }
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(flatMap);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(flatMap));
                        }
                    } else {
                        MethodCallExpr doOnSuccess;
                        if (hasCheckAwaitIsNull) {
                            doOnSuccess = new MethodCallExpr("switchIfEmpty")
                                    .addArgument(
                                            new MethodCallExpr("defer")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .setEnclosingParameters(true)
                                                                    .setBody(new BlockStmt(buildAsyncStatements(getAwaitIsNullStatementList(variableDeclarator.getNameAsString(), lastStatementList), defaultIfEmpty)))
                                                    )
                                                    .setScope(new NameExpr(Mono.class.getSimpleName()))
                                    )
                                    .setScope(
                                            new MethodCallExpr("doOnSuccess")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                                    .setBody(new BlockStmt(buildAsyncStatements(getAwaitIsNotNullStatementList(variableDeclarator.getNameAsString(), lastStatementList), defaultIfEmpty)))

                                                    )
                                                    .setScope(methodCallExpr)
                                    );
                        } else {
                            doOnSuccess = new MethodCallExpr("doOnSuccess")
                                    .addArgument(
                                            new LambdaExpr()
                                                    .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                    .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                    )
                                    .setScope(methodCallExpr);
                        }
                        Optional<NodeList<Statement>> parentReturnOrThrowStatementList = statement.getParentNode()
                                .flatMap(node -> getParentReturnOrThrowStatementList(node, defaultIfEmpty));
                        if (parentReturnOrThrowStatementList.isPresent()) {
                            doOnSuccess = new MethodCallExpr("then")
                                    .setScope(doOnSuccess)
                                    .addArgument(
                                            new MethodCallExpr("defer")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .setEnclosingParameters(true)
                                                                    .setBody(new BlockStmt(parentReturnOrThrowStatementList.get()))
                                                    )
                                                    .setScope(new NameExpr(Mono.class.getSimpleName()))
                                    );
                        }
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(doOnSuccess);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(doOnSuccess));
                        }
                    }
                    break;
                } else if (methodDeclarationReturnTypeName.equals(Flux.class.getCanonicalName())) {
                    if (lastHasReturnOrThrow) {
                        MethodCallExpr flatMap;
                        if (hasCheckAwaitIsNull) {
                            flatMap = new MethodCallExpr("switchIfEmpty")
                                    .addArgument(
                                            new MethodCallExpr("defer")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .setEnclosingParameters(true)
                                                                    .setBody(new BlockStmt(buildAsyncStatements(getAwaitIsNullStatementList(variableDeclarator.getNameAsString(), lastStatementList), defaultIfEmpty)))
                                                    )
                                                    .setScope(new NameExpr(Mono.class.getSimpleName()))
                                    )
                                    .setScope(
                                            new MethodCallExpr("flatMap")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                                    .setBody(new BlockStmt(buildAsyncStatements(getAwaitIsNotNullStatementList(variableDeclarator.getNameAsString(), lastStatementList), defaultIfEmpty)))
                                                    )
                                                    .setScope(
                                                            new MethodCallExpr("collectList")
                                                                    .setScope(methodCallExpr)
                                                    )
                                    );
                        } else {
                            flatMap = new MethodCallExpr("flatMap")
                                    .addArgument(
                                            new LambdaExpr()
                                                    .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                    .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                    )
                                    .setScope(
                                            new MethodCallExpr("collectList")
                                                    .setScope(methodCallExpr)
                                    );
                        }
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(flatMap);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(flatMap));
                        }
                    } else if (lastHasAwait) {
                        MethodCallExpr flatMap;
                        if (hasCheckAwaitIsNull) {
                            flatMap = new MethodCallExpr("switchIfEmpty")
                                    .addArgument(
                                            new MethodCallExpr("defer")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .setEnclosingParameters(true)
                                                                    .setBody(new BlockStmt(buildAsyncStatements(getAwaitIsNullStatementList(variableDeclarator.getNameAsString(), lastStatementList), defaultIfEmpty)))
                                                    )
                                                    .setScope(new NameExpr(Mono.class.getSimpleName()))
                                    )
                                    .setScope(
                                            new MethodCallExpr("flatMap")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                                    .setBody(new BlockStmt(buildAsyncStatements(getAwaitIsNotNullStatementList(variableDeclarator.getNameAsString(), lastStatementList), defaultIfEmpty)))
                                                    )
                                                    .setScope(
                                                            new MethodCallExpr("collectList")
                                                                    .setScope(methodCallExpr)
                                                    )
                                    );
                        } else {
                            flatMap = new MethodCallExpr("flatMap")
                                    .addArgument(
                                            new LambdaExpr()
                                                    .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                    .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                    )
                                    .setScope(
                                            new MethodCallExpr("collectList")
                                                    .setScope(methodCallExpr)
                                    );
                        }
                        Optional<NodeList<Statement>> parentReturnOrThrowStatementList = statement.getParentNode()
                                .flatMap(node -> getParentReturnOrThrowStatementList(node, defaultIfEmpty));
                        if (parentReturnOrThrowStatementList.isPresent()) {
                            flatMap = new MethodCallExpr("then")
                                    .setScope(flatMap)
                                    .addArgument(
                                            new MethodCallExpr("defer")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .setEnclosingParameters(true)
                                                                    .setBody(new BlockStmt(parentReturnOrThrowStatementList.get()))
                                                    )
                                                    .setScope(new NameExpr(Mono.class.getSimpleName()))
                                    );
                        }
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(flatMap);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(flatMap));
                        }
                    } else {
                        MethodCallExpr doOnSuccess;
                        if (hasCheckAwaitIsNull) {
                            doOnSuccess = new MethodCallExpr("switchIfEmpty")
                                    .addArgument(
                                            new MethodCallExpr("defer")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .setEnclosingParameters(true)
                                                                    .setBody(new BlockStmt(buildAsyncStatements(getAwaitIsNullStatementList(variableDeclarator.getNameAsString(), lastStatementList), defaultIfEmpty)))
                                                    )
                                                    .setScope(new NameExpr(Mono.class.getSimpleName()))
                                    )
                                    .setScope(
                                            new MethodCallExpr("doOnSuccess")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                                    .setBody(new BlockStmt(buildAsyncStatements(getAwaitIsNotNullStatementList(variableDeclarator.getNameAsString(), lastStatementList), defaultIfEmpty)))
                                                    )
                                                    .setScope(
                                                            new MethodCallExpr("collectList")
                                                                    .setScope(methodCallExpr)
                                                    )
                                    );
                        } else {
                            doOnSuccess = new MethodCallExpr("doOnSuccess")
                                    .addArgument(
                                            new LambdaExpr()
                                                    .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                    .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                    )
                                    .setScope(
                                            new MethodCallExpr("collectList")
                                                    .setScope(methodCallExpr)
                                    );
                        }
                        Optional<NodeList<Statement>> parentReturnOrThrowStatementList = statement.getParentNode()
                                .flatMap(node -> getParentReturnOrThrowStatementList(node, defaultIfEmpty));
                        if (parentReturnOrThrowStatementList.isPresent()) {
                            doOnSuccess = new MethodCallExpr("then")
                                    .setScope(doOnSuccess)
                                    .addArgument(
                                            new MethodCallExpr("defer")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .setEnclosingParameters(true)
                                                                    .setBody(new BlockStmt(parentReturnOrThrowStatementList.get()))
                                                    )
                                                    .setScope(new NameExpr(Mono.class.getSimpleName()))
                                    );
                        }
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(doOnSuccess);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(doOnSuccess));
                        }
                    }
                    break;
                } else {
                    String asyncMethodName = Stream
                            .concat(
                                    Stream.of(methodCallExpr.getNameAsString() + ASYNC_METHOD_NAME_SUFFIX),
                                    processorManager.resolveMethodDeclarationParameterTypeNames(methodCallExpr)
                            )
                            .collect(Collectors.joining("_"));

                    MethodCallExpr asyncMethodCallExpr = new MethodCallExpr("async")
                            .setArguments(
                                    Stream
                                            .concat(
                                                    Stream.of(new StringLiteralExpr(asyncMethodName)),
                                                    methodCallExpr.getArguments().stream()
                                            )
                                            .collect(Collectors.toCollection(NodeList::new))
                            );
                    methodCallExpr.getScope().ifPresent(asyncMethodCallExpr::setScope);

                    if (lastHasReturnOrThrow) {
                        MethodCallExpr flatMap;
                        if (hasCheckAwaitIsNull) {
                            flatMap = new MethodCallExpr("switchIfEmpty")
                                    .addArgument(
                                            new MethodCallExpr("defer")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .setEnclosingParameters(true)
                                                                    .setBody(new BlockStmt(buildAsyncStatements(getAwaitIsNullStatementList(variableDeclarator.getNameAsString(), lastStatementList), defaultIfEmpty)))
                                                    )
                                                    .setScope(new NameExpr(Mono.class.getSimpleName()))
                                    )
                                    .setScope(
                                            new MethodCallExpr("flatMap")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                                    .setBody(new BlockStmt(buildAsyncStatements(getAwaitIsNotNullStatementList(variableDeclarator.getNameAsString(), lastStatementList), defaultIfEmpty)))
                                                    )
                                                    .setScope(
                                                            new MethodCallExpr("map")
                                                                    .addArgument(
                                                                            new LambdaExpr()
                                                                                    .addParameter(new Parameter(new UnknownType(), "result"))
                                                                                    .setBody(new ExpressionStmt(new CastExpr().setType(methodDeclarationReturnDescribe).setExpression(new NameExpr("result"))))
                                                                    )
                                                                    .setScope(asyncMethodCallExpr)
                                                    )
                                    );
                        } else {
                            flatMap = new MethodCallExpr("flatMap")
                                    .addArgument(
                                            new LambdaExpr()
                                                    .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                    .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                    )
                                    .setScope(
                                            new MethodCallExpr("map")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .addParameter(new Parameter(new UnknownType(), "result"))
                                                                    .setBody(new ExpressionStmt(new CastExpr().setType(methodDeclarationReturnDescribe).setExpression(new NameExpr("result"))))
                                                    )
                                                    .setScope(asyncMethodCallExpr)
                                    );
                        }
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(flatMap);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(flatMap));
                        }
                    } else if (lastHasAwait) {
                        MethodCallExpr flatMap;
                        if (hasCheckAwaitIsNull) {
                            flatMap = new MethodCallExpr("switchIfEmpty")
                                    .addArgument(
                                            new MethodCallExpr("defer")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .setEnclosingParameters(true)
                                                                    .setBody(new BlockStmt(buildAsyncStatements(getAwaitIsNullStatementList(variableDeclarator.getNameAsString(), lastStatementList), defaultIfEmpty)))
                                                    )
                                                    .setScope(new NameExpr(Mono.class.getSimpleName()))
                                    )
                                    .setScope(
                                            new MethodCallExpr("flatMap")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                                    .setBody(new BlockStmt(buildAsyncStatements(getAwaitIsNotNullStatementList(variableDeclarator.getNameAsString(), lastStatementList), defaultIfEmpty)))
                                                    )
                                                    .setScope(
                                                            new MethodCallExpr("map")
                                                                    .addArgument(
                                                                            new LambdaExpr()
                                                                                    .addParameter(new Parameter(new UnknownType(), "result"))
                                                                                    .setBody(new ExpressionStmt(new CastExpr().setType(methodDeclarationReturnDescribe).setExpression(new NameExpr("result"))))
                                                                    )
                                                                    .setScope(asyncMethodCallExpr)
                                                    )
                                    );
                        } else {
                            flatMap = new MethodCallExpr("flatMap")
                                    .addArgument(
                                            new LambdaExpr()
                                                    .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                    .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                    )
                                    .setScope(
                                            new MethodCallExpr("map")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .addParameter(new Parameter(new UnknownType(), "result"))
                                                                    .setBody(new ExpressionStmt(new CastExpr().setType(methodDeclarationReturnDescribe).setExpression(new NameExpr("result"))))
                                                    )
                                                    .setScope(asyncMethodCallExpr)
                                    );
                        }
                        Optional<NodeList<Statement>> parentReturnOrThrowStatementList = statement.getParentNode()
                                .flatMap(node -> getParentReturnOrThrowStatementList(node, defaultIfEmpty));
                        if (parentReturnOrThrowStatementList.isPresent()) {
                            flatMap = new MethodCallExpr("then")
                                    .setScope(flatMap)
                                    .addArgument(
                                            new MethodCallExpr("defer")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .setEnclosingParameters(true)
                                                                    .setBody(new BlockStmt(parentReturnOrThrowStatementList.get()))
                                                    )
                                                    .setScope(new NameExpr(Mono.class.getSimpleName()))
                                    );
                        }
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(flatMap);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(flatMap));
                        }
                    } else {
                        MethodCallExpr doOnSuccess;
                        if (hasCheckAwaitIsNull) {
                            doOnSuccess = new MethodCallExpr("switchIfEmpty")
                                    .addArgument(
                                            new MethodCallExpr("defer")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .setEnclosingParameters(true)
                                                                    .setBody(new BlockStmt(buildAsyncStatements(getAwaitIsNullStatementList(variableDeclarator.getNameAsString(), lastStatementList), defaultIfEmpty)))
                                                    )
                                                    .setScope(new NameExpr(Mono.class.getSimpleName()))
                                    )
                                    .setScope(
                                            new MethodCallExpr("doOnSuccess")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                                    .setBody(new BlockStmt(buildAsyncStatements(getAwaitIsNotNullStatementList(variableDeclarator.getNameAsString(), lastStatementList), defaultIfEmpty)))
                                                    )
                                                    .setScope(
                                                            new MethodCallExpr("map")
                                                                    .addArgument(
                                                                            new LambdaExpr()
                                                                                    .addParameter(new Parameter(new UnknownType(), "result"))
                                                                                    .setBody(new ExpressionStmt(new CastExpr().setType(methodDeclarationReturnDescribe).setExpression(new NameExpr("result"))))
                                                                    )
                                                                    .setScope(asyncMethodCallExpr)
                                                    )
                                    );
                        } else {
                            doOnSuccess = new MethodCallExpr("doOnSuccess")
                                    .addArgument(
                                            new LambdaExpr()
                                                    .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                    .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                    )
                                    .setScope(
                                            new MethodCallExpr("map")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .addParameter(new Parameter(new UnknownType(), "result"))
                                                                    .setBody(new ExpressionStmt(new CastExpr().setType(methodDeclarationReturnDescribe).setExpression(new NameExpr("result"))))
                                                    )
                                                    .setScope(asyncMethodCallExpr)
                                    );
                        }
                        Optional<NodeList<Statement>> parentReturnOrThrowStatementList = statement.getParentNode()
                                .flatMap(node -> getParentReturnOrThrowStatementList(node, defaultIfEmpty));
                        if (parentReturnOrThrowStatementList.isPresent()) {
                            doOnSuccess = new MethodCallExpr("then")
                                    .setScope(doOnSuccess)
                                    .addArgument(
                                            new MethodCallExpr("defer")
                                                    .addArgument(
                                                            new LambdaExpr()
                                                                    .setEnclosingParameters(true)
                                                                    .setBody(new BlockStmt(parentReturnOrThrowStatementList.get()))
                                                    )
                                                    .setScope(new NameExpr(Mono.class.getSimpleName()))
                                    );
                        }
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(doOnSuccess);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(doOnSuccess));
                        }
                    }
                    break;
                }
            } else if (statement.isBlockStmt()) {
                if (hasAwait(statement.asBlockStmt().getStatements()) && !hasReturnOrThrowStmt(statement.asBlockStmt().getStatements())) {
                    asyncStatements.add(new BlockStmt().setStatements(buildAsyncStatements(Stream.concat(statement.asBlockStmt().getStatements().stream(), lastStatementList.stream()).collect(Collectors.toList()), defaultIfEmpty)));
                } else {
                    asyncStatements.add(new BlockStmt().setStatements(buildAsyncStatements(statement.asBlockStmt().getStatements(), defaultIfEmpty)));
                }
            } else if (statement.isIfStmt()) {
                boolean ifStmtHasAwait = ifStmtHasAwait(statement.asIfStmt());
                boolean ifStmtHasReturnOrThrow = ifStmtHasReturnOrThrowStmt(statement.asIfStmt());
                IfStmt asyncIfStmt = buildIfStmt(statementNodeList, i, statement.asIfStmt(), defaultIfEmpty);
                asyncStatements.add(asyncIfStmt);
                if ((ifStmtHasAwait || ifStmtHasReturnOrThrow) && ifStmtLastIsElse(asyncIfStmt)) {
                    break;
                }
            } else if (statement.isForStmt()) {
                if (statement.asForStmt().getBody().isBlockStmt()) {
                    if (hasAwait(statement.asForStmt().getBody().asBlockStmt().getStatements())) {
                        MethodCallExpr flatMap = new MethodCallExpr("flatMap")
                                .addArgument(
                                        new LambdaExpr()
                                                .addParameter(new Parameter(new UnknownType(), statement.asForStmt().getInitialization().get(0).asVariableDeclarationExpr().getVariable(0).getNameAsString()))
                                                .setBody(new BlockStmt(buildAsyncStatements(statement.asForStmt().getBody().asBlockStmt().getStatements(), defaultIfEmpty)))
                                )
                                .setScope(
                                        new MethodCallExpr("range")
                                                .addArgument(
                                                        statement.asForStmt().getInitialization().get(0).asVariableDeclarationExpr().getVariable(0).getInitializer()
                                                                .map(expression -> expression.asAssignExpr().getValue())
                                                                .orElseThrow(() -> new RuntimeException("missing assignment in statement: " + statement))
                                                )
                                                .addArgument(
                                                        statement.asForStmt().getCompare()
                                                                .map(expression -> expression.asBinaryExpr().getRight())
                                                                .orElseThrow(() -> new RuntimeException("missing binary in statement: " + statement))
                                                )
                                                .setScope(new NameExpr(Flux.class.getSimpleName()))
                                );
                        asyncStatements.add(
                                new ReturnStmt(
                                        new MethodCallExpr("then")
                                                .addArgument(
                                                        new MethodCallExpr("defer")
                                                                .addArgument(
                                                                        new LambdaExpr()
                                                                                .setEnclosingParameters(true)
                                                                                .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                                                )
                                                                .setScope(new NameExpr(Mono.class.getSimpleName()))
                                                )
                                                .setScope(flatMap)
                                )
                        );
                        break;
                    } else {
                        ForStmt forStmt = new ForStmt();
                        forStmt.setInitialization(statement.asForStmt().getInitialization());
                        statement.asForStmt().getCompare().ifPresent(forStmt::setCompare);
                        forStmt.setUpdate(statement.asForStmt().getUpdate());
                        asyncStatements.add(forStmt.setBody(new BlockStmt().setStatements(buildAsyncStatements(statement.asForStmt().getBody().asBlockStmt().getStatements(), defaultIfEmpty))));
                    }
                } else if (statement.asForStmt().getBody().isReturnStmt()) {
                    ForStmt forStmt = new ForStmt();
                    forStmt.setInitialization(statement.asForStmt().getInitialization());
                    statement.asForStmt().getCompare().ifPresent(forStmt::setCompare);
                    forStmt.setUpdate(statement.asForStmt().getUpdate());
                    buildAsyncReturnExpression(statement.asForStmt().getBody().asReturnStmt())
                            .ifPresent(expression -> forStmt.setBody(new ReturnStmt(expression)));
                    asyncStatements.add(forStmt);
                }
            } else if (statement.isForEachStmt()) {
                if (statement.asForEachStmt().getBody().isBlockStmt()) {
                    if (hasAwait(statement.asForEachStmt().getBody().asBlockStmt().getStatements())) {
                        MethodCallExpr flatMap = new MethodCallExpr("flatMap")
                                .addArgument(
                                        new LambdaExpr()
                                                .addParameter(new Parameter(new UnknownType(), statement.asForEachStmt().getVariable().getVariable(0).getNameAsString()))
                                                .setBody(new BlockStmt(buildAsyncStatements(statement.asForEachStmt().getBody().asBlockStmt().getStatements(), defaultIfEmpty)))
                                )
                                .setScope(
                                        new MethodCallExpr("fromIterable")
                                                .addArgument(statement.asForEachStmt().getIterable())
                                                .setScope(new NameExpr(Flux.class.getSimpleName()))
                                );

                        NodeList<Statement> statements = buildAsyncStatements(lastStatementList, defaultIfEmpty);
                        MethodCallExpr then = new MethodCallExpr("then")
                                .setScope(flatMap);
                        if (!statements.isEmpty()) {
                            then.addArgument(
                                    new MethodCallExpr("defer")
                                            .addArgument(
                                                    new LambdaExpr()
                                                            .setEnclosingParameters(true)
                                                            .setBody(new BlockStmt(statements))
                                            )
                                            .setScope(new NameExpr(Mono.class.getSimpleName()))
                            );
                        }
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(then);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(then));
                        }
                        break;
                    } else {
                        ForEachStmt forEachStmt = new ForEachStmt();
                        forEachStmt.setVariable(statement.asForEachStmt().getVariable());
                        forEachStmt.setIterable(statement.asForEachStmt().getIterable());
                        asyncStatements.add(forEachStmt.setBody(new BlockStmt().setStatements(buildAsyncStatements(statement.asForEachStmt().getBody().asBlockStmt().getStatements(), defaultIfEmpty))));
                    }
                } else if (statement.asForEachStmt().getBody().isReturnStmt()) {
                    ForEachStmt forEachStmt = new ForEachStmt();
                    forEachStmt.setVariable(statement.asForEachStmt().getVariable());
                    forEachStmt.setIterable(statement.asForEachStmt().getIterable());
                    buildAsyncReturnExpression(statement.asForEachStmt().getBody().asReturnStmt())
                            .ifPresent(expression -> forEachStmt.setBody(new ReturnStmt(expression)));
                    asyncStatements.add(forEachStmt);
                }
            } else if (statement.isTryStmt()) {
                TryStmt tryStmt = new TryStmt();
                tryStmt.setResources(statement.asTryStmt().getResources());
                if (hasAwait(statement.asTryStmt().getTryBlock().getStatements()) && !hasReturnOrThrowStmt(statement.asTryStmt().getTryBlock().getStatements())) {
                    asyncStatements.add(tryStmt.setTryBlock(new BlockStmt().setStatements(buildAsyncStatements(Stream.concat(statement.asTryStmt().getTryBlock().getStatements().stream(), lastStatementList.stream()).collect(Collectors.toList()), defaultIfEmpty))));
                } else {
                    asyncStatements.add(tryStmt.setTryBlock(new BlockStmt().setStatements(buildAsyncStatements(statement.asTryStmt().getTryBlock().getStatements(), defaultIfEmpty))));
                }
                tryStmt.setCatchClauses(
                        statement.asTryStmt().getCatchClauses().stream()
                                .map(catchClause -> {
                                            CatchClause asyncCatchClause = new CatchClause();
                                            asyncCatchClause.setParameter(catchClause.getParameter());
                                            if (hasAwait(catchClause.getBody().getStatements()) && !hasReturnOrThrowStmt(catchClause.getBody().getStatements())) {
                                                asyncCatchClause.setBody(new BlockStmt().setStatements(buildAsyncStatements(Stream.concat(catchClause.getBody().getStatements().stream(), lastStatementList.stream()).collect(Collectors.toList()), defaultIfEmpty)));
                                            } else {
                                                asyncCatchClause.setBody(new BlockStmt().setStatements(buildAsyncStatements(catchClause.getBody().getStatements(), defaultIfEmpty)));
                                            }
                                            return asyncCatchClause;
                                        }
                                )
                                .collect(Collectors.toCollection(NodeList::new))
                );
                if (statement.asTryStmt().getFinallyBlock().isPresent()) {
                    if (hasAwait(statement.asTryStmt().getFinallyBlock().get().getStatements()) && !hasReturnOrThrowStmt(statement.asTryStmt().getFinallyBlock().get().getStatements())) {
                        tryStmt.setFinallyBlock(new BlockStmt().setStatements(buildAsyncStatements(Stream.concat(statement.asTryStmt().getFinallyBlock().get().getStatements().stream(), lastStatementList.stream()).collect(Collectors.toList()), defaultIfEmpty)));
                    } else {
                        tryStmt.setFinallyBlock(new BlockStmt().setStatements(buildAsyncStatements(statement.asTryStmt().getFinallyBlock().get().getStatements(), defaultIfEmpty)));
                    }
                }
                asyncStatements.add(tryStmt);
            } else if (statement.isSwitchStmt()) {
                SwitchStmt switchStmt = new SwitchStmt();
                switchStmt.setSelector(statement.asSwitchStmt().getSelector());
                switchStmt.setEntries(
                        statement.asSwitchStmt().getEntries().stream()
                                .map(switchEntry -> {
                                            SwitchEntry asyncSwitchEntry = new SwitchEntry();
                                            if (hasAwait(switchEntry.getStatements()) && !hasReturnOrThrowStmt(switchEntry.getStatements())) {
                                                asyncSwitchEntry.setStatements(buildAsyncStatements(Stream.concat(switchEntry.getStatements().stream(), lastStatementList.stream()).collect(Collectors.toList()), defaultIfEmpty));
                                            } else {
                                                asyncSwitchEntry.setStatements(buildAsyncStatements(switchEntry.getStatements(), defaultIfEmpty));
                                            }
                                            return asyncSwitchEntry;
                                        }
                                )
                                .collect(Collectors.toCollection(NodeList::new))
                );
                asyncStatements.add(switchStmt);
            } else if (statement.isThrowStmt()) {
                asyncStatements.add(
                        new ReturnStmt(
                                new MethodCallExpr("error")
                                        .addArgument(
                                                statement.asThrowStmt().getExpression()
                                        )
                                        .setScope(new NameExpr(Mono.class.getSimpleName()))
                        )
                );
                break;
            } else if (statement.isReturnStmt()) {
                buildAsyncReturnExpression(statement.asReturnStmt())
                        .ifPresent(expression -> asyncStatements.add(new ReturnStmt(expression)));
            } else {
                if (!statement.isExpressionStmt() ||
                        !statement.asExpressionStmt().getExpression().isVariableDeclarationExpr() ||
                        statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0).getInitializer().isPresent() ||
                        !variableDeclaratorIsAwait(statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0), lastStatementList)) {
                    asyncStatements.add(statement.clone());
                }
            }
        }
        return asyncStatements;
    }

    private boolean hasReturnOrThrowStmt(List<Statement> statementList) {
        return statementList.stream()
                .anyMatch(statement -> {
                            if (statement.isReturnStmt() || statement.isThrowStmt()) {
                                return true;
                            } else if (statement.isBlockStmt()) {
                                return hasReturnOrThrowStmt(statement.asBlockStmt().getStatements());
                            } else if (statement.isIfStmt()) {
                                return ifStmtHasReturnOrThrowStmt(statement.asIfStmt());
                            } else if (statement.isForStmt() && statement.asForStmt().getBody().isBlockStmt()) {
                                return hasReturnOrThrowStmt(statement.asForStmt().getBody().asBlockStmt().getStatements());
                            } else if (statement.isForEachStmt() && statement.asForEachStmt().getBody().isBlockStmt()) {
                                return hasReturnOrThrowStmt(statement.asForEachStmt().getBody().asBlockStmt().getStatements());
                            } else if (statement.isTryStmt()) {
                                return hasReturnOrThrowStmt(statement.asTryStmt().getTryBlock().getStatements()) ||
                                        statement.asTryStmt().getCatchClauses().stream().anyMatch(catchClause -> hasReturnOrThrowStmt(catchClause.getBody().getStatements())) ||
                                        statement.asTryStmt().getFinallyBlock().isPresent() && hasReturnOrThrowStmt(statement.asTryStmt().getFinallyBlock().get().getStatements());
                            } else if (statement.isSwitchStmt()) {
                                return statement.asSwitchStmt().getEntries().stream().anyMatch(switchEntry -> hasReturnOrThrowStmt(switchEntry.getStatements()));
                            }
                            return false;
                        }
                );
    }

    private boolean hasAwait(List<Statement> statementList) {
        return statementList.stream().anyMatch(this::hasAwait);
    }

    private boolean hasAwait(Statement statement) {
        if (statement.isExpressionStmt() &&
                (
                        statement.asExpressionStmt().getExpression().isMethodCallExpr() &&
                                statement.asExpressionStmt().getExpression().asMethodCallExpr().getNameAsString().equals("await") &&
                                (statement.asExpressionStmt().getExpression().asMethodCallExpr().getArgument(0).isMethodCallExpr() ||
                                        statement.asExpressionStmt().getExpression().asMethodCallExpr().getArgument(0).isLambdaExpr() &&
                                                statement.asExpressionStmt().getExpression().asMethodCallExpr().getArgument(0).asLambdaExpr().getBody().isExpressionStmt() &&
                                                statement.asExpressionStmt().getExpression().asMethodCallExpr().getArgument(0).asLambdaExpr().getBody().asExpressionStmt().getExpression().isMethodCallExpr()
                                ) ||
                                (statement.asExpressionStmt().getExpression().isVariableDeclarationExpr() &&
                                        statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariables().size() == 1 &&
                                        statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0).getInitializer().isPresent() &&
                                        statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0).getInitializer().get().isMethodCallExpr() &&
                                        statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0).getInitializer().get().asMethodCallExpr().getNameAsString().equals("await") &&
                                        statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0).getInitializer().get().asMethodCallExpr().getArgument(0).isMethodCallExpr()) ||
                                statement.asExpressionStmt().getExpression().isAssignExpr() &&
                                        statement.asExpressionStmt().getExpression().asAssignExpr().getValue().isMethodCallExpr() &&
                                        statement.asExpressionStmt().getExpression().asAssignExpr().getValue().asMethodCallExpr().getNameAsString().equals("await") &&
                                        statement.asExpressionStmt().getExpression().asAssignExpr().getValue().asMethodCallExpr().getArgument(0).isMethodCallExpr())
        ) {
            return true;
        } else if (statement.isBlockStmt()) {
            return hasAwait(statement.asBlockStmt().getStatements());
        } else if (statement.isIfStmt()) {
            return ifStmtHasAwait(statement.asIfStmt());
        } else if (statement.isForStmt() && statement.asForStmt().getBody().isBlockStmt()) {
            return hasAwait(statement.asForStmt().getBody().asBlockStmt().getStatements());
        } else if (statement.isForEachStmt() && statement.asForEachStmt().getBody().isBlockStmt()) {
            return hasAwait(statement.asForEachStmt().getBody().asBlockStmt().getStatements());
        } else if (statement.isTryStmt()) {
            return hasAwait(statement.asTryStmt().getTryBlock().getStatements()) ||
                    statement.asTryStmt().getCatchClauses().stream().anyMatch(catchClause -> hasAwait(catchClause.getBody().getStatements())) ||
                    statement.asTryStmt().getFinallyBlock().isPresent() && hasAwait(statement.asTryStmt().getFinallyBlock().get().getStatements());
        } else if (statement.isSwitchStmt()) {
            return statement.asSwitchStmt().getEntries().stream().anyMatch(switchEntry -> hasAwait(switchEntry.getStatements()));
        }
        return false;
    }

    private boolean hasCheckAwaitIsNull(String variableName, List<Statement> statementList) {
        return statementList.stream()
                .anyMatch(statement ->
                        statement.isIfStmt() &&
                                statement.asIfStmt().getCondition().isBinaryExpr() &&
                                (statement.asIfStmt().getCondition().asBinaryExpr().getOperator().equals(EQUALS) ||
                                        statement.asIfStmt().getCondition().asBinaryExpr().getOperator().equals(NOT_EQUALS)) &&
                                (statement.asIfStmt().getCondition().asBinaryExpr().getRight().isNameExpr() &&
                                        statement.asIfStmt().getCondition().asBinaryExpr().getRight().asNameExpr().getNameAsString().equals(variableName) ||
                                        statement.asIfStmt().getCondition().asBinaryExpr().getLeft().isNameExpr() &&
                                                statement.asIfStmt().getCondition().asBinaryExpr().getLeft().asNameExpr().getNameAsString().equals(variableName))
                );
    }

    private List<Statement> getAwaitIsNullStatementList(String variableName, List<Statement> statementList) {
        return statementList.stream()
                .flatMap(statement -> {
                            if (statement.isIfStmt() &&
                                    statement.asIfStmt().getCondition().isBinaryExpr() &&
                                    (statement.asIfStmt().getCondition().asBinaryExpr().getOperator().equals(EQUALS) ||
                                            statement.asIfStmt().getCondition().asBinaryExpr().getOperator().equals(NOT_EQUALS)) &&
                                    (statement.asIfStmt().getCondition().asBinaryExpr().getRight().isNameExpr() &&
                                            statement.asIfStmt().getCondition().asBinaryExpr().getRight().asNameExpr().getNameAsString().equals(variableName) ||
                                            statement.asIfStmt().getCondition().asBinaryExpr().getLeft().isNameExpr() &&
                                                    statement.asIfStmt().getCondition().asBinaryExpr().getLeft().asNameExpr().getNameAsString().equals(variableName))) {
                                if (statement.asIfStmt().getCondition().asBinaryExpr().getOperator().equals(EQUALS)) {
                                    return statement.asIfStmt().getThenStmt().asBlockStmt().getStatements().stream();
                                } else {
                                    if (statement.asIfStmt().getElseStmt().isPresent()) {
                                        return statement.asIfStmt().getElseStmt().get().asBlockStmt().getStatements().stream();
                                    } else {
                                        return Stream.empty();
                                    }
                                }
                            } else {
                                return Stream.of(statement);
                            }
                        }
                )
                .collect(Collectors.toList());
    }

    private List<Statement> getAwaitIsNotNullStatementList(String variableName, List<Statement> statementList) {
        return statementList.stream()
                .flatMap(statement -> {
                            if (statement.isIfStmt() &&
                                    statement.asIfStmt().getCondition().isBinaryExpr() &&
                                    (statement.asIfStmt().getCondition().asBinaryExpr().getOperator().equals(EQUALS) ||
                                            statement.asIfStmt().getCondition().asBinaryExpr().getOperator().equals(NOT_EQUALS)) &&
                                    (statement.asIfStmt().getCondition().asBinaryExpr().getRight().isNameExpr() &&
                                            statement.asIfStmt().getCondition().asBinaryExpr().getRight().asNameExpr().getNameAsString().equals(variableName) ||
                                            statement.asIfStmt().getCondition().asBinaryExpr().getLeft().isNameExpr() &&
                                                    statement.asIfStmt().getCondition().asBinaryExpr().getLeft().asNameExpr().getNameAsString().equals(variableName))) {
                                if (statement.asIfStmt().getCondition().asBinaryExpr().getOperator().equals(NOT_EQUALS)) {
                                    return statement.asIfStmt().getThenStmt().asBlockStmt().getStatements().stream();
                                } else {
                                    if (statement.asIfStmt().getElseStmt().isPresent()) {
                                        return statement.asIfStmt().getElseStmt().get().asBlockStmt().getStatements().stream();
                                    } else {
                                        return Stream.empty();
                                    }
                                }
                            } else {
                                return Stream.of(statement);
                            }
                        }
                )
                .collect(Collectors.toList());
    }

    private boolean variableDeclaratorIsAwait(VariableDeclarator variableDeclarator, List<Statement> statementList) {
        return statementList.stream()
                .anyMatch(statement -> {
                            if (statement.isExpressionStmt() &&
                                    statement.asExpressionStmt().getExpression().isAssignExpr() &&
                                    statement.asExpressionStmt().getExpression().asAssignExpr().getTarget().isNameExpr() &&
                                    statement.asExpressionStmt().getExpression().asAssignExpr().getTarget().asNameExpr().getNameAsString().equals(variableDeclarator.getNameAsString()) &&
                                    statement.asExpressionStmt().getExpression().asAssignExpr().getValue().isMethodCallExpr() &&
                                    statement.asExpressionStmt().getExpression().asAssignExpr().getValue().asMethodCallExpr().getNameAsString().equals("await")
                            ) {
                                return true;
                            } else if (statement.isIfStmt()) {
                                IfStmt ifStmt = statement.asIfStmt();
                                return variableDeclaratorIsAwait(variableDeclarator, ifStmt);
                            } else {
                                return false;
                            }
                        }
                );
    }

    private boolean variableDeclaratorIsAwait(VariableDeclarator variableDeclarator, IfStmt ifStmt) {
        boolean variableDeclaratorIsAwait = false;
        if (ifStmt.getThenStmt().isBlockStmt()) {
            variableDeclaratorIsAwait = variableDeclaratorIsAwait(variableDeclarator, ifStmt.getThenStmt().asBlockStmt().getStatements());
        }

        boolean variableDeclaratorIsAwaitInElse = false;
        if (ifStmt.getElseStmt().isPresent()) {
            if (ifStmt.getElseStmt().get().isBlockStmt()) {
                variableDeclaratorIsAwaitInElse = variableDeclaratorIsAwait(variableDeclarator, ifStmt.getElseStmt().get().asBlockStmt().getStatements());
            } else if (ifStmt.getElseStmt().get().isIfStmt()) {
                variableDeclaratorIsAwaitInElse = variableDeclaratorIsAwait(variableDeclarator, ifStmt.getElseStmt().get().asIfStmt());
            }
        }
        return variableDeclaratorIsAwait || variableDeclaratorIsAwaitInElse;
    }

    private boolean hasAwait(ReturnStmt returnStmt) {
        return returnStmt.getExpression().stream()
                .anyMatch(expression ->
                        expression.isMethodCallExpr() &&
                                expression.asMethodCallExpr().getNameAsString().equals("await") &&
                                expression.asMethodCallExpr().getArgument(0).isMethodCallExpr()
                );
    }

    private boolean ifStmtHasReturnOrThrowStmt(IfStmt ifStmt) {
        if (ifStmt.getThenStmt().isReturnStmt() || ifStmt.getThenStmt().isThrowStmt()) {
            return true;
        } else if (ifStmt.getThenStmt().isBlockStmt()) {
            return hasReturnOrThrowStmt(ifStmt.getThenStmt().asBlockStmt().getStatements());
        }

        if (ifStmt.getElseStmt().isPresent()) {
            if (ifStmt.getElseStmt().get().isReturnStmt() || ifStmt.getElseStmt().get().isThrowStmt()) {
                return true;
            } else if (ifStmt.getElseStmt().get().isBlockStmt()) {
                return hasReturnOrThrowStmt(ifStmt.getElseStmt().get().asBlockStmt().getStatements());
            } else if (ifStmt.getElseStmt().get().isIfStmt()) {
                return ifStmtHasReturnOrThrowStmt(ifStmt.getElseStmt().get().asIfStmt());
            }
        }
        return false;
    }

    private boolean ifStmtHasAwait(IfStmt ifStmt) {
        boolean hasAwait = false;
        if (ifStmt.getThenStmt().isReturnStmt()) {
            hasAwait = hasAwait(ifStmt.getThenStmt().asReturnStmt());
        } else if (ifStmt.getThenStmt().isBlockStmt()) {
            hasAwait = hasAwait(ifStmt.getThenStmt().asBlockStmt().getStatements());
        }

        boolean elseHasAwait = false;
        if (ifStmt.getElseStmt().isPresent()) {
            if (ifStmt.getElseStmt().get().isReturnStmt()) {
                elseHasAwait = hasAwait(ifStmt.getElseStmt().get().asReturnStmt());
            } else if (ifStmt.getElseStmt().get().isBlockStmt()) {
                elseHasAwait = hasAwait(ifStmt.getElseStmt().get().asBlockStmt().getStatements());
            } else if (ifStmt.getElseStmt().get().isIfStmt()) {
                elseHasAwait = ifStmtHasAwait(ifStmt.getElseStmt().get().asIfStmt());
            }
        }
        return hasAwait || elseHasAwait;
    }

    private boolean ifStmtLastIsElse(IfStmt ifStmt) {
        if (ifStmt.getElseStmt().isPresent()) {
            if (ifStmt.getElseStmt().get().isBlockStmt()) {
                return true;
            } else {
                return ifStmtLastIsElse(ifStmt.getElseStmt().get().asIfStmt());
            }
        }
        return false;
    }

    private IfStmt buildIfStmt(List<Statement> statementNodeList, int i, IfStmt ifStmt, String defaultIfEmpty) {
        IfStmt asyncIfStmt = new IfStmt();
        asyncIfStmt.setCondition(ifStmt.getCondition());
        List<Statement> lastStatementList = statementNodeList.subList(i + 1, statementNodeList.size());
        boolean hasAwait = false;
        boolean hasReturnOrThrow = false;
        if (ifStmt.getThenStmt().isBlockStmt()) {
            hasAwait = hasAwait(ifStmt.getThenStmt().asBlockStmt().getStatements());
            hasReturnOrThrow = hasReturnOrThrowStmt(ifStmt.getThenStmt().asBlockStmt().getStatements());
            if (hasAwait && !hasReturnOrThrow) {
                asyncIfStmt.setThenStmt(new BlockStmt().setStatements(buildAsyncStatements(Stream.concat(ifStmt.getThenStmt().asBlockStmt().getStatements().stream(), lastStatementList.stream()).collect(Collectors.toList()), defaultIfEmpty)));
            } else {
                asyncIfStmt.setThenStmt(new BlockStmt().setStatements(buildAsyncStatements(ifStmt.getThenStmt().asBlockStmt().getStatements(), defaultIfEmpty)));
            }
        } else if (ifStmt.getThenStmt().isThrowStmt()) {
            hasReturnOrThrow = true;
            asyncIfStmt.setThenStmt(new BlockStmt().setStatements(buildAsyncStatements(Collections.singletonList(ifStmt.getThenStmt().asThrowStmt()), defaultIfEmpty)));
        } else if (ifStmt.getThenStmt().isReturnStmt()) {
            hasAwait = hasAwait(ifStmt.getThenStmt().asReturnStmt());
            hasReturnOrThrow = true;
            buildAsyncReturnExpression(ifStmt.getThenStmt().asReturnStmt())
                    .ifPresent(expression -> asyncIfStmt.setThenStmt(new ReturnStmt(expression)));
        }

        if (ifStmt.getElseStmt().isPresent()) {
            if (ifStmt.getElseStmt().get().isIfStmt()) {
                asyncIfStmt.setElseStmt(buildIfStmt(statementNodeList, i, ifStmt.getElseStmt().get().asIfStmt(), defaultIfEmpty));
            } else if (ifStmt.getElseStmt().get().isBlockStmt()) {
                if (hasAwait(ifStmt.getElseStmt().get().asBlockStmt().getStatements()) && !hasReturnOrThrowStmt(ifStmt.getElseStmt().get().asBlockStmt().getStatements())) {
                    asyncIfStmt.setElseStmt(new BlockStmt().setStatements(buildAsyncStatements(Stream.concat(ifStmt.getElseStmt().get().asBlockStmt().getStatements().stream(), lastStatementList.stream()).collect(Collectors.toList()), defaultIfEmpty)));
                } else {
                    asyncIfStmt.setElseStmt(new BlockStmt().setStatements(buildAsyncStatements(ifStmt.getElseStmt().get().asBlockStmt().getStatements(), defaultIfEmpty)));
                }
            } else if (ifStmt.getElseStmt().get().isThrowStmt()) {
                asyncIfStmt.setElseStmt(new BlockStmt().setStatements(buildAsyncStatements(Collections.singletonList(ifStmt.getElseStmt().get().asThrowStmt()), defaultIfEmpty)));
            } else if (ifStmt.getElseStmt().get().isReturnStmt()) {
                buildAsyncReturnExpression(ifStmt.getElseStmt().get().asReturnStmt())
                        .ifPresent(expression -> asyncIfStmt.setElseStmt(new ReturnStmt(expression)));
            }
        } else {
            if (hasReturnOrThrow || hasAwait) {
                getParentReturnOrThrowStatementList(ifStmt, defaultIfEmpty)
                        .ifPresentOrElse(nodeList ->
                                        asyncIfStmt.setElseStmt(
                                                new BlockStmt()
                                                        .addStatement(
                                                                new ReturnStmt(
                                                                        new MethodCallExpr("defer")
                                                                                .addArgument(
                                                                                        new LambdaExpr()
                                                                                                .setEnclosingParameters(true)
                                                                                                .setBody(new BlockStmt(nodeList))
                                                                                )
                                                                                .setScope(new NameExpr(Mono.class.getSimpleName()))
                                                                )
                                                        )
                                        ),
                                () -> asyncIfStmt.setElseStmt(
                                        new BlockStmt()
                                                .addStatement(
                                                        new ReturnStmt(
                                                                new MethodCallExpr("empty")
                                                                        .setScope(new NameExpr(Mono.class.getSimpleName()))
                                                        )
                                                )
                                )
                        );
            }
        }
        return asyncIfStmt;
    }

    private Optional<NodeList<Statement>> getParentReturnOrThrowStatementList(Node node, String defaultIsEmpty) {
        if (node instanceof ForStmt || node instanceof ForEachStmt) {
            return Optional.empty();
        }
        return node.getParentNode()
                .flatMap(parent -> {
                            if (parent instanceof NodeWithStatements) {
                                List<Statement> statementList = ((NodeWithStatements<? extends Node>) parent).getStatements();
                                List<Statement> lastStatementList = statementList.subList(statementList.indexOf((Statement) node) + 1, statementList.size());
                                if (hasReturnOrThrowStmt(lastStatementList) || hasAwait(lastStatementList)) {
                                    return Optional.of(buildAsyncStatements(lastStatementList, defaultIsEmpty));
                                } else {
                                    return getParentReturnOrThrowStatementList(parent, defaultIsEmpty);
                                }
                            } else {
                                return getParentReturnOrThrowStatementList(parent, defaultIsEmpty);
                            }
                        }
                );
    }

    private Optional<Expression> buildAsyncReturnExpression(ReturnStmt returnStmt) {
        return returnStmt.getExpression()
                .map(expression -> {
                            if (expression.isMethodCallExpr()) {
                                if (expression.asMethodCallExpr().getNameAsString().equals("await") &&
                                        expression.asMethodCallExpr().getArgument(0).isMethodCallExpr()) {
                                    MethodCallExpr methodCallExpr = expression.asMethodCallExpr().getArgument(0).asMethodCallExpr();
                                    String methodDeclarationReturnTypeName = processorManager.resolveMethodDeclarationReturnTypeQualifiedName(methodCallExpr);
                                    if (methodDeclarationReturnTypeName.equals(Mono.class.getCanonicalName())) {
                                        return expression.asMethodCallExpr().getArgument(0);
                                    } else {
                                        String asyncMethodName = Stream
                                                .concat(
                                                        Stream.of(methodCallExpr.getNameAsString() + ASYNC_METHOD_NAME_SUFFIX),
                                                        processorManager.resolveMethodDeclarationParameterTypeNames(methodCallExpr)
                                                )
                                                .collect(Collectors.joining("_"));

                                        MethodCallExpr asyncMethodCallExpr = new MethodCallExpr("async")
                                                .setArguments(
                                                        Stream
                                                                .concat(
                                                                        Stream.of(new StringLiteralExpr(asyncMethodName)),
                                                                        methodCallExpr.getArguments().stream()
                                                                )
                                                                .collect(Collectors.toCollection(NodeList::new))
                                                );
                                        methodCallExpr.getScope().ifPresent(asyncMethodCallExpr::setScope);
                                        return asyncMethodCallExpr;
                                    }
                                }
                                String methodDeclarationReturnTypeName = processorManager.resolveMethodDeclarationReturnTypeQualifiedName(expression.asMethodCallExpr());
                                if (methodDeclarationReturnTypeName.equals(Mono.class.getCanonicalName())) {
                                    return expression;
                                }
                            } else if (expression.isNullLiteralExpr()) {
                                return new MethodCallExpr("empty")
                                        .setScope(new NameExpr(Mono.class.getSimpleName()));
                            }
                            return new MethodCallExpr("just")
                                    .addArgument(expression)
                                    .setScope(new NameExpr(Mono.class.getSimpleName()));
                        }
                );
    }

    private Optional<MethodDeclaration> buildAsyncMethodDeclaration(ClassOrInterfaceDeclaration componentClassDeclaration) {
        List<MethodDeclaration> asyncMethodDeclarationList = componentClassDeclaration.getMethods().stream()
                .filter(methodDeclaration -> methodDeclaration.isAnnotationPresent(Async.class))
                .collect(Collectors.toList());
        return buildAsyncMethodDeclaration(componentClassDeclaration, asyncMethodDeclarationList);
    }

    private Optional<MethodDeclaration> buildAsyncMethodDeclaration(ClassOrInterfaceDeclaration componentClassDeclaration,
                                                                    List<MethodDeclaration> asyncMethodDeclarationList) {
        if (asyncMethodDeclarationList.isEmpty()) {
            return Optional.empty();
        }
        MethodDeclaration asyncMethodDeclaration = buildAsyncableMethodDeclarationFromElements();

        SwitchStmt switchStmt = new SwitchStmt()
                .setSelector(asyncMethodDeclaration.getParameter(0).getNameAsExpression())
                .setEntries(
                        Stream
                                .concat(
                                        asyncMethodDeclarationList.stream()
                                                .map(methodDeclaration -> {
                                                            String asyncMethodName = Stream
                                                                    .concat(
                                                                            Stream.of(methodDeclaration.getNameAsString() + ASYNC_METHOD_NAME_SUFFIX),
                                                                            methodDeclaration.getParameters().stream()
                                                                                    .map(parameter -> {
                                                                                                if (parameter.getType().isPrimitiveType()) {
                                                                                                    return parameter.getType().asPrimitiveType().toBoxedType().getNameAsString();
                                                                                                } else if (parameter.getType().isClassOrInterfaceType()) {
                                                                                                    return parameter.getType().asClassOrInterfaceType().getNameAsString();
                                                                                                } else {
                                                                                                    return parameter.getTypeAsString();
                                                                                                }
                                                                                            }
                                                                                    )
                                                                    )
                                                                    .collect(Collectors.joining("_"));
                                                            return new SwitchEntry()
                                                                    .setLabels(new NodeList<>(new StringLiteralExpr(asyncMethodName)))
                                                                    .addStatement(
                                                                            new ReturnStmt(
                                                                                    new CastExpr()
                                                                                            .setExpression(
                                                                                                    new MethodCallExpr(asyncMethodName)
                                                                                                            .setArguments(
                                                                                                                    IntStream.range(0, methodDeclaration.getParameters().size())
                                                                                                                            .mapToObj(index ->
                                                                                                                                    new CastExpr()
                                                                                                                                            .setExpression(
                                                                                                                                                    new ArrayAccessExpr()
                                                                                                                                                            .setName(new NameExpr(asyncMethodDeclaration.getParameter(1).getNameAsString()))
                                                                                                                                                            .setIndex(new IntegerLiteralExpr(String.valueOf(index)))
                                                                                                                                            )
                                                                                                                                            .setType(methodDeclaration.getParameter(index).getType())
                                                                                                                            )
                                                                                                                            .collect(Collectors.toCollection(NodeList::new))
                                                                                                            )
                                                                                            )
                                                                                            .setType(asyncMethodDeclaration.getType())
                                                                            )
                                                                    );
                                                        }
                                                ),
                                        Stream.of(
                                                new SwitchEntry()
                                                        .addStatement(
                                                                new ThrowStmt(
                                                                        new ObjectCreationExpr()
                                                                                .setType(
                                                                                        new ClassOrInterfaceType()
                                                                                                .setName(RuntimeException.class.getSimpleName())
                                                                                )
                                                                                .addArgument(
                                                                                        new BinaryExpr()
                                                                                                .setLeft(new StringLiteralExpr("method not found: "))
                                                                                                .setOperator(BinaryExpr.Operator.PLUS)
                                                                                                .setRight(new NameExpr("asyncMethodName"))
                                                                                )
                                                                )
                                                        )
                                        )
                                )
                                .collect(Collectors.toCollection(NodeList::new))
                );

        return Optional.of(
                new MethodDeclaration()
                        .setName("async")
                        .setModifiers(Modifier.Keyword.PUBLIC)
                        .setType(asyncMethodDeclaration.getType())
                        .setParameters(asyncMethodDeclaration.getParameters())
                        .setTypeParameters(asyncMethodDeclaration.getTypeParameters())
                        .addAnnotation(Override.class)
                        .setBody(new BlockStmt().addStatement(switchStmt))
        );
    }

    private MethodDeclaration buildAsyncableMethodDeclarationFromElements() {
        TypeElement asyncableElement = processorManager.getTypeElement(Asyncable.class.getCanonicalName())
                .orElseThrow(() -> new IllegalStateException("Asyncable type not found"));
        ExecutableElement asyncElement = asyncableElement.getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.METHOD)
                .map(element -> (ExecutableElement) element)
                .filter(element -> element.getSimpleName().toString().equals("async"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Asyncable.async method not found"));

        MethodDeclaration methodDeclaration = new MethodDeclaration()
                .setName(asyncElement.getSimpleName().toString())
                .setModifiers(Modifier.Keyword.PUBLIC);

        asyncElement.getTypeParameters()
                .forEach(typeParameter -> methodDeclaration.addTypeParameter(typeParameter.getSimpleName().toString()));

        methodDeclaration.setType(StaticJavaParser.parseType(asyncElement.getReturnType().toString()));

        List<Parameter> parameters = asyncElement.getParameters().stream()
                .map(parameter -> new Parameter()
                        .setType(StaticJavaParser.parseType(getParameterTypeName(parameter.asType(), asyncElement.isVarArgs())))
                        .setName(parameter.getSimpleName().toString()))
                .collect(Collectors.toList());
        if (asyncElement.isVarArgs() && !parameters.isEmpty()) {
            parameters.get(parameters.size() - 1).setVarArgs(true);
        }
        methodDeclaration.setParameters(new NodeList<>(parameters));

        return methodDeclaration;
    }

    private String getParameterTypeName(TypeMirror typeMirror, boolean isVarArgs) {
        if (isVarArgs && typeMirror instanceof ArrayType) {
            return ((ArrayType) typeMirror).getComponentType().toString();
        }
        return typeMirror.toString();
    }
}
