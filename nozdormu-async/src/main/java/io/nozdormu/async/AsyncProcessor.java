package io.nozdormu.async;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.UnknownType;
import com.google.auto.service.AutoService;
import io.nozdormu.common.ProcessorManager;
import io.nozdormu.inject.processor.ComponentProxyProcessor;
import io.nozdormu.spi.async.Async;
import io.nozdormu.spi.async.Asyncable;
import jakarta.inject.Provider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.nozdormu.spi.async.Asyncable.ASYNC_METHOD_NAME_SUFFIX;

@AutoService(ComponentProxyProcessor.class)
public class AsyncProcessor implements ComponentProxyProcessor {

    private ProcessorManager processorManager;

    @Override
    public void init(ProcessorManager processorManager) {
        this.processorManager = processorManager;
    }

    @Override
    public void processComponentProxy(CompilationUnit componentCompilationUnit, ClassOrInterfaceDeclaration componentClassDeclaration, CompilationUnit componentProxyCompilationUnit, ClassOrInterfaceDeclaration componentProxyClassDeclaration) {
        componentClassDeclaration.getMethods().stream()
                .filter(methodDeclaration -> methodDeclaration.isAnnotationPresent(Async.class))
                .forEach(methodDeclaration -> {
                            Optional<BlockStmt> blockStmtOptional = methodDeclaration.getBody();
                            if (blockStmtOptional.isPresent()) {
                                componentProxyCompilationUnit.addImport(Mono.class);
                                BlockStmt blockStmt = blockStmtOptional.get();
                                String asyncMethodName = Stream
                                        .concat(
                                                Stream.of(methodDeclaration.getNameAsString() + ASYNC_METHOD_NAME_SUFFIX),
                                                methodDeclaration.getParameters().stream()
                                                        .map(parameter -> {
                                                                    if (parameter.getType().isPrimitiveType()) {
                                                                        return parameter.getType().asPrimitiveType().toBoxedType().getNameAsString();
                                                                    } else {
                                                                        return parameter.getTypeAsString();
                                                                    }
                                                                }
                                                        )
                                        )
                                        .collect(Collectors.joining("_"));
                                MethodDeclaration asyncMethodDeclaration = new MethodDeclaration();
                                asyncMethodDeclaration.setParentNode(componentProxyClassDeclaration);
                                asyncMethodDeclaration.setName(asyncMethodName)
                                        .setModifiers(methodDeclaration.getModifiers())
                                        .setParameters(methodDeclaration.getParameters())
                                        .setType(new ClassOrInterfaceType().setName(Mono.class.getSimpleName()).setTypeArguments(methodDeclaration.getType()))
                                        .setBody(buildAsyncMethodBodyBlockStmt(null, componentClassDeclaration, blockStmt.getStatements()));
                                componentProxyClassDeclaration.addMember(asyncMethodDeclaration);
                            }
                        }
                );
        buildAsyncMethodDeclaration(componentClassDeclaration).ifPresent(componentProxyClassDeclaration::addMember);
    }

    protected BlockStmt buildAsyncMethodBodyBlockStmt(Node parentNode, ClassOrInterfaceDeclaration componentClassDeclaration, List<Statement> statementNodeList) {
        BlockStmt body = new BlockStmt();
        body.setStatements(buildAsyncMethodBody(parentNode, componentClassDeclaration, statementNodeList));
        return body;
    }

    protected NodeList<Statement> buildAsyncMethodBody(Node parentNode, ClassOrInterfaceDeclaration componentClassDeclaration, List<Statement> statementNodeList) {
        if (parentNode != null) {
            for (Statement statement : statementNodeList) {
                statement.setParentNode(parentNode);
            }
        }
        boolean hasReturnStmt = hasReturnStmt(statementNodeList);
        boolean hasAwait = hasAwait(statementNodeList);
        NodeList<Statement> statements = new NodeList<>();
        for (int i = 0; i < statementNodeList.size(); i++) {
            Statement statement = statementNodeList.get(i);
            if (statement.isExpressionStmt() &&
                    statement.asExpressionStmt().getExpression().isMethodCallExpr() &&
                    statement.asExpressionStmt().getExpression().asMethodCallExpr().getNameAsString().equals("await")
            ) {
                MethodCallExpr methodCallExpr = statement.asExpressionStmt().getExpression().asMethodCallExpr().getArgument(0).asMethodCallExpr();
                String methodDeclarationReturnTypeName = processorManager.resolveMethodDeclarationReturnTypeQualifiedName(componentClassDeclaration, methodCallExpr);
                if (methodCallExpr.getScope().isPresent() && processorManager.calculateType(methodCallExpr.getScope().get()).asReferenceType().getQualifiedName().equals(Provider.class.getCanonicalName()) ||
                        methodDeclarationReturnTypeName.equals(Mono.class.getCanonicalName())) {
                    List<Statement> statementList = statementNodeList.subList(i + 1, statementNodeList.size());
                    if (statementList.isEmpty()) {
                        MethodCallExpr thenEmpty = new MethodCallExpr("then")
                                .setScope(methodCallExpr);
                        statements.add(new ReturnStmt(thenEmpty));
                    } else if (hasReturnStmt) {
                        MethodCallExpr then = new MethodCallExpr("then")
                                .addArgument(
                                        new MethodCallExpr("defer")
                                                .addArgument(
                                                        new LambdaExpr()
                                                                .setEnclosingParameters(true)
                                                                .setBody(buildAsyncMethodBodyBlockStmt(parentNode, componentClassDeclaration, statementList))
                                                )
                                                .setScope(new NameExpr(Mono.class.getSimpleName()))
                                )
                                .setScope(methodCallExpr);
                        statements.add(new ReturnStmt(then));
                    } else if (hasAwait) {
                        MethodCallExpr thenEmpty = new MethodCallExpr("thenEmpty")
                                .addArgument(
                                        new MethodCallExpr("defer")
                                                .addArgument(
                                                        new LambdaExpr()
                                                                .setEnclosingParameters(true)
                                                                .setBody(buildAsyncMethodBodyBlockStmt(parentNode, componentClassDeclaration, statementList))
                                                )
                                                .setScope(new NameExpr(Mono.class.getSimpleName()))
                                )
                                .setScope(methodCallExpr);
                        statements.add(new ReturnStmt(thenEmpty));
                    } else {
                        MethodCallExpr thenEmpty = new MethodCallExpr("thenEmpty")
                                .addArgument(
                                        new MethodCallExpr("fromRunnable")
                                                .addArgument(
                                                        new LambdaExpr()
                                                                .setEnclosingParameters(true)
                                                                .setBody(buildAsyncMethodBodyBlockStmt(parentNode, componentClassDeclaration, statementList))
                                                )
                                                .setScope(new NameExpr(Mono.class.getSimpleName()))
                                )
                                .setScope(methodCallExpr);
                        statements.add(new ReturnStmt(thenEmpty));
                    }
                    break;
                } else if (methodDeclarationReturnTypeName.equals(Flux.class.getCanonicalName())) {
                    List<Statement> statementList = statementNodeList.subList(i + 1, statementNodeList.size());
                    if (statementList.isEmpty()) {
                        MethodCallExpr then = new MethodCallExpr("then")
                                .setScope(
                                        new MethodCallExpr("collectList")
                                                .setScope(methodCallExpr)
                                );
                        statements.add(new ReturnStmt(then));
                    } else if (hasReturnStmt) {
                        MethodCallExpr then = new MethodCallExpr("then")
                                .addArgument(
                                        new MethodCallExpr("defer")
                                                .addArgument(
                                                        new LambdaExpr()
                                                                .setEnclosingParameters(true)
                                                                .setBody(buildAsyncMethodBodyBlockStmt(parentNode, componentClassDeclaration, statementList))
                                                )
                                                .setScope(new NameExpr(Mono.class.getSimpleName()))
                                )
                                .setScope(
                                        new MethodCallExpr("collectList")
                                                .setScope(methodCallExpr)
                                );
                        statements.add(new ReturnStmt(then));
                    } else if (hasAwait) {
                        MethodCallExpr thenEmpty = new MethodCallExpr("thenEmpty")
                                .addArgument(
                                        new MethodCallExpr("defer")
                                                .addArgument(
                                                        new LambdaExpr()
                                                                .setEnclosingParameters(true)
                                                                .setBody(buildAsyncMethodBodyBlockStmt(parentNode, componentClassDeclaration, statementList))
                                                )
                                                .setScope(new NameExpr(Mono.class.getSimpleName()))
                                )
                                .setScope(
                                        new MethodCallExpr("collectList")
                                                .setScope(methodCallExpr)
                                );
                        statements.add(new ReturnStmt(thenEmpty));
                    } else {
                        MethodCallExpr thenEmpty = new MethodCallExpr("thenEmpty")
                                .addArgument(
                                        new MethodCallExpr("fromRunnable")
                                                .addArgument(
                                                        new LambdaExpr()
                                                                .setEnclosingParameters(true)
                                                                .setBody(buildAsyncMethodBodyBlockStmt(parentNode, componentClassDeclaration, statementList))
                                                )
                                                .setScope(new NameExpr(Mono.class.getSimpleName()))
                                )
                                .setScope(
                                        new MethodCallExpr("collectList")
                                                .setScope(methodCallExpr)
                                );
                        statements.add(new ReturnStmt(thenEmpty));
                    }
                    break;
                } else {
                    String asyncMethodName = Stream
                            .concat(
                                    Stream.of(methodCallExpr.getNameAsString() + ASYNC_METHOD_NAME_SUFFIX),
                                    processorManager.resolveMethodDeclarationParameterTypeNames(componentClassDeclaration, methodCallExpr)
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
                    List<Statement> statementList = statementNodeList.subList(i + 1, statementNodeList.size());
                    if (statementList.isEmpty()) {
                        MethodCallExpr then = new MethodCallExpr("then")
                                .setScope(asyncMethodCallExpr);
                        statements.add(new ReturnStmt(then));
                    } else if (hasReturnStmt) {
                        MethodCallExpr then = new MethodCallExpr("then")
                                .addArgument(
                                        new MethodCallExpr("defer")
                                                .addArgument(
                                                        new LambdaExpr()
                                                                .setEnclosingParameters(true)
                                                                .setBody(buildAsyncMethodBodyBlockStmt(parentNode, componentClassDeclaration, statementList))
                                                )
                                                .setScope(new NameExpr(Mono.class.getSimpleName()))
                                )
                                .setScope(asyncMethodCallExpr);
                        statements.add(new ReturnStmt(then));
                    } else if (hasAwait) {
                        MethodCallExpr thenEmpty = new MethodCallExpr("thenEmpty")
                                .addArgument(
                                        new MethodCallExpr("defer")
                                                .addArgument(
                                                        new LambdaExpr()
                                                                .setEnclosingParameters(true)
                                                                .setBody(buildAsyncMethodBodyBlockStmt(parentNode, componentClassDeclaration, statementList))
                                                )
                                                .setScope(new NameExpr(Mono.class.getSimpleName()))
                                )
                                .setScope(asyncMethodCallExpr);
                        statements.add(new ReturnStmt(thenEmpty));
                    } else {
                        MethodCallExpr thenEmpty = new MethodCallExpr("thenEmpty")
                                .addArgument(
                                        new MethodCallExpr("fromRunnable")
                                                .addArgument(
                                                        new LambdaExpr()
                                                                .setEnclosingParameters(true)
                                                                .setBody(buildAsyncMethodBodyBlockStmt(parentNode, componentClassDeclaration, statementList))
                                                )
                                                .setScope(new NameExpr(Mono.class.getSimpleName()))
                                )
                                .setScope(asyncMethodCallExpr);
                        statements.add(new ReturnStmt(thenEmpty));
                    }
                    break;
                }
            } else if (statement.isExpressionStmt() &&
                    statement.asExpressionStmt().getExpression().isVariableDeclarationExpr() &&
                    statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariables().size() == 1 &&
                    statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0).getInitializer().isPresent() &&
                    statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0).getInitializer().get().isMethodCallExpr() &&
                    statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0).getInitializer().get().asMethodCallExpr().getNameAsString().equals("await")
            ) {
                VariableDeclarator variableDeclarator = statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0);
                MethodCallExpr methodCallExpr = variableDeclarator.getInitializer().get().asMethodCallExpr().getArgument(0).asMethodCallExpr();
                String methodDeclarationReturnTypeName = processorManager.resolveMethodDeclarationReturnTypeQualifiedName(componentClassDeclaration, methodCallExpr);
                if (methodCallExpr.getScope().isPresent() && processorManager.calculateType(methodCallExpr.getScope().get()).asReferenceType().getQualifiedName().equals(Provider.class.getCanonicalName()) ||
                        methodDeclarationReturnTypeName.equals(Mono.class.getCanonicalName())) {
                    if (hasReturnStmt) {
                        MethodCallExpr flatMap = new MethodCallExpr("flatMap")
                                .addArgument(
                                        new LambdaExpr()
                                                .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                .setBody(buildAsyncMethodBodyBlockStmt(parentNode, componentClassDeclaration, statementNodeList.subList(i + 1, statementNodeList.size())))
                                )
                                .setScope(methodCallExpr);
                        statements.add(new ReturnStmt(flatMap));
                    } else if (hasAwait) {
                        MethodCallExpr doOnSuccess = new MethodCallExpr("flatMap")
                                .addArgument(
                                        new LambdaExpr()
                                                .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                .setBody(buildAsyncMethodBodyBlockStmt(parentNode, componentClassDeclaration, statementNodeList.subList(i + 1, statementNodeList.size())))
                                )
                                .setScope(methodCallExpr);
                        statements.add(new ReturnStmt(new MethodCallExpr("then").setScope(doOnSuccess)));
                    } else {
                        MethodCallExpr doOnSuccess = new MethodCallExpr("doOnSuccess")
                                .addArgument(
                                        new LambdaExpr()
                                                .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                .setBody(buildAsyncMethodBodyBlockStmt(parentNode, componentClassDeclaration, statementNodeList.subList(i + 1, statementNodeList.size())))
                                )
                                .setScope(methodCallExpr);
                        statements.add(new ReturnStmt(new MethodCallExpr("then").setScope(doOnSuccess)));
                    }
                    break;
                } else if (methodDeclarationReturnTypeName.equals(Flux.class.getCanonicalName())) {
                    if (hasReturnStmt) {
                        MethodCallExpr flatMap = new MethodCallExpr("flatMap")
                                .addArgument(
                                        new LambdaExpr()
                                                .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                .setBody(buildAsyncMethodBodyBlockStmt(parentNode, componentClassDeclaration, statementNodeList.subList(i + 1, statementNodeList.size())))
                                )
                                .setScope(
                                        new MethodCallExpr("collectList")
                                                .setScope(methodCallExpr)
                                );
                        statements.add(new ReturnStmt(flatMap));
                    } else if (hasAwait) {
                        MethodCallExpr doOnSuccess = new MethodCallExpr("flatMap")
                                .addArgument(
                                        new LambdaExpr()
                                                .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                .setBody(buildAsyncMethodBodyBlockStmt(parentNode, componentClassDeclaration, statementNodeList.subList(i + 1, statementNodeList.size())))
                                )
                                .setScope(
                                        new MethodCallExpr("collectList")
                                                .setScope(methodCallExpr)
                                );
                        statements.add(new ReturnStmt(new MethodCallExpr("then").setScope(doOnSuccess)));
                    } else {
                        MethodCallExpr doOnSuccess = new MethodCallExpr("doOnSuccess")
                                .addArgument(
                                        new LambdaExpr()
                                                .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                .setBody(buildAsyncMethodBodyBlockStmt(parentNode, componentClassDeclaration, statementNodeList.subList(i + 1, statementNodeList.size())))
                                )
                                .setScope(
                                        new MethodCallExpr("collectList")
                                                .setScope(methodCallExpr)
                                );
                        statements.add(new ReturnStmt(new MethodCallExpr("then").setScope(doOnSuccess)));
                    }
                    break;
                } else {
                    String asyncMethodName = Stream
                            .concat(
                                    Stream.of(methodCallExpr.getNameAsString() + ASYNC_METHOD_NAME_SUFFIX),
                                    processorManager.resolveMethodDeclarationParameterTypeNames(componentClassDeclaration, methodCallExpr)
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

                    if (hasReturnStmt) {
                        MethodCallExpr flatMap = new MethodCallExpr("flatMap")
                                .addArgument(
                                        new LambdaExpr()
                                                .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                .setBody(buildAsyncMethodBodyBlockStmt(parentNode, componentClassDeclaration, statementNodeList.subList(i + 1, statementNodeList.size())))
                                )
                                .setScope(
                                        new MethodCallExpr("map")
                                                .addArgument(
                                                        new LambdaExpr()
                                                                .addParameter(new Parameter(new UnknownType(), "result"))
                                                                .setBody(new ExpressionStmt(new CastExpr().setType(methodDeclarationReturnTypeName).setExpression(new NameExpr("result"))))
                                                )
                                                .setScope(asyncMethodCallExpr)
                                );
                        statements.add(new ReturnStmt(flatMap));
                    } else if (hasAwait) {
                        MethodCallExpr doOnSuccess = new MethodCallExpr("flatMap")
                                .addArgument(
                                        new LambdaExpr()
                                                .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                .setBody(buildAsyncMethodBodyBlockStmt(parentNode, componentClassDeclaration, statementNodeList.subList(i + 1, statementNodeList.size())))
                                )
                                .setScope(
                                        new MethodCallExpr("map")
                                                .addArgument(
                                                        new LambdaExpr()
                                                                .addParameter(new Parameter(new UnknownType(), "result"))
                                                                .setBody(new ExpressionStmt(new CastExpr().setType(methodDeclarationReturnTypeName).setExpression(new NameExpr("result"))))
                                                )
                                                .setScope(asyncMethodCallExpr)
                                );
                        statements.add(new ReturnStmt(new MethodCallExpr("then").setScope(doOnSuccess)));
                    } else {
                        MethodCallExpr doOnSuccess = new MethodCallExpr("doOnSuccess")
                                .addArgument(
                                        new LambdaExpr()
                                                .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                .setBody(buildAsyncMethodBodyBlockStmt(parentNode, componentClassDeclaration, statementNodeList.subList(i + 1, statementNodeList.size())))
                                )
                                .setScope(
                                        new MethodCallExpr("map")
                                                .addArgument(
                                                        new LambdaExpr()
                                                                .addParameter(new Parameter(new UnknownType(), "result"))
                                                                .setBody(new ExpressionStmt(new CastExpr().setType(methodDeclarationReturnTypeName).setExpression(new NameExpr("result"))))
                                                )
                                                .setScope(asyncMethodCallExpr)
                                );
                        statements.add(new ReturnStmt(new MethodCallExpr("then").setScope(doOnSuccess)));
                    }
                    break;
                }
            } else if (statement.isBlockStmt()) {
                if (hasAwait(statement.asBlockStmt().getStatements()) && !hasReturnStmt(statement.asBlockStmt().getStatements())) {
                    statement.asBlockStmt().setStatements(buildAsyncMethodBody(statement.asBlockStmt().getParentNode().orElse(null), componentClassDeclaration, Stream.concat(statement.asBlockStmt().getStatements().stream(), statementNodeList.subList(i + 1, statementNodeList.size()).stream()).collect(Collectors.toList())));
                } else {
                    statement.asBlockStmt().setStatements(buildAsyncMethodBody(statement.asBlockStmt().getParentNode().orElse(null), componentClassDeclaration, statement.asBlockStmt().getStatements()));
                }
                statements.add(statement);
            } else if (statement.isIfStmt()) {
                buildIfStmt(parentNode, componentClassDeclaration, statementNodeList, i, statement.asIfStmt());
                statements.add(statement);
            } else if (statement.isTryStmt()) {
                if (hasAwait(statement.asTryStmt().getTryBlock().getStatements()) && !hasReturnStmt(statement.asTryStmt().getTryBlock().getStatements())) {
                    statement.asTryStmt().getTryBlock().setStatements(buildAsyncMethodBody(statement.asTryStmt().getTryBlock().getParentNode().orElse(null), componentClassDeclaration, Stream.concat(statement.asTryStmt().getTryBlock().getStatements().stream(), statementNodeList.subList(i + 1, statementNodeList.size()).stream()).collect(Collectors.toList())));
                } else {
                    statement.asTryStmt().getTryBlock().setStatements(buildAsyncMethodBody(statement.asTryStmt().getTryBlock().getParentNode().orElse(null), componentClassDeclaration, statement.asTryStmt().getTryBlock().getStatements()));
                }
                for (CatchClause catchClause : statement.asTryStmt().getCatchClauses()) {
                    if (hasAwait(catchClause.getBody().getStatements()) && !hasReturnStmt(catchClause.getBody().getStatements())) {
                        catchClause.getBody().setStatements(buildAsyncMethodBody(catchClause.getBody().getParentNode().orElse(null), componentClassDeclaration, Stream.concat(catchClause.getBody().getStatements().stream(), statementNodeList.subList(i + 1, statementNodeList.size()).stream()).collect(Collectors.toList())));
                    } else {
                        catchClause.getBody().setStatements(buildAsyncMethodBody(catchClause.getBody().getParentNode().orElse(null), componentClassDeclaration, catchClause.getBody().getStatements()));
                    }
                }
                if (statement.asTryStmt().getFinallyBlock().isPresent()) {
                    if (hasAwait(statement.asTryStmt().getFinallyBlock().get().getStatements()) && !hasReturnStmt(statement.asTryStmt().getFinallyBlock().get().getStatements())) {
                        statement.asTryStmt().getFinallyBlock().get().setStatements(buildAsyncMethodBody(statement.asTryStmt().getFinallyBlock().get().getParentNode().orElse(null), componentClassDeclaration, Stream.concat(statement.asTryStmt().getFinallyBlock().get().getStatements().stream(), statementNodeList.subList(i + 1, statementNodeList.size()).stream()).collect(Collectors.toList())));
                    } else {
                        statement.asTryStmt().getFinallyBlock().get().setStatements(buildAsyncMethodBody(statement.asTryStmt().getFinallyBlock().get().getParentNode().orElse(null), componentClassDeclaration, statement.asTryStmt().getFinallyBlock().get().getStatements()));
                    }
                }
                statements.add(statement);
            } else if (statement.isSwitchStmt()) {
                for (SwitchEntry switchEntry : statement.asSwitchStmt().getEntries()) {
                    if (hasAwait(switchEntry.getStatements()) && !hasReturnStmt(switchEntry.getStatements())) {
                        switchEntry.setStatements(buildAsyncMethodBody(switchEntry.getParentNode().orElse(null), componentClassDeclaration, Stream.concat(switchEntry.getStatements().stream(), statementNodeList.subList(i + 1, statementNodeList.size()).stream()).collect(Collectors.toList())));
                    } else {
                        switchEntry.setStatements(buildAsyncMethodBody(switchEntry.getParentNode().orElse(null), componentClassDeclaration, switchEntry.getStatements()));
                    }
                }
                statements.add(statement);
            } else if (statement.isReturnStmt()) {
                buildAsyncReturnExpression(componentClassDeclaration, statement.asReturnStmt())
                        .ifPresent(expression -> statement.asReturnStmt().setExpression(expression));
                statements.add(statement);
            } else {
                statements.add(statement);
            }
        }
        if (parentNode != null) {
            for (Statement statement : statements) {
                statement.setParentNode(parentNode);
            }
        }
        return statements;
    }

    private boolean hasReturnStmt(List<Statement> statementList) {
        return statementList.stream()
                .anyMatch(statement -> {
                            if (statement.isReturnStmt()) {
                                return true;
                            } else if (statement.isBlockStmt()) {
                                return hasReturnStmt(statement.asBlockStmt().getStatements());
                            } else if (statement.isIfStmt()) {
                                return ifStmtHasReturnStmt(statement.asIfStmt());
                            } else if (statement.isTryStmt()) {
                                return hasReturnStmt(statement.asTryStmt().getTryBlock().getStatements()) ||
                                        statement.asTryStmt().getCatchClauses().stream().anyMatch(catchClause -> hasReturnStmt(catchClause.getBody().getStatements())) ||
                                        statement.asTryStmt().getFinallyBlock().isPresent() && hasReturnStmt(statement.asTryStmt().getFinallyBlock().get().getStatements());
                            } else if (statement.isSwitchStmt()) {
                                return statement.asSwitchStmt().getEntries().stream().anyMatch(switchEntry -> hasReturnStmt(switchEntry.getStatements()));
                            }
                            return false;
                        }
                );
    }

    private boolean hasAwait(List<Statement> statementList) {
        return statementList.stream()
                .anyMatch(statement -> {
                            if (statement.isExpressionStmt() &&
                                    (
                                            statement.asExpressionStmt().getExpression().isMethodCallExpr() &&
                                                    statement.asExpressionStmt().getExpression().asMethodCallExpr().getNameAsString().equals("await") ||
                                                    statement.asExpressionStmt().getExpression().isVariableDeclarationExpr() &&
                                                            statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariables().size() == 1 &&
                                                            statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0).getInitializer().isPresent() &&
                                                            statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0).getInitializer().get().isMethodCallExpr() &&
                                                            statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0).getInitializer().get().asMethodCallExpr().getNameAsString().equals("await")
                                    )
                            ) {
                                return true;
                            } else if (statement.isBlockStmt()) {
                                return hasAwait(statement.asBlockStmt().getStatements());
                            } else if (statement.isIfStmt()) {
                                return ifStmtHasAwait(statement.asIfStmt());
                            } else if (statement.isTryStmt()) {
                                return hasAwait(statement.asTryStmt().getTryBlock().getStatements()) ||
                                        statement.asTryStmt().getCatchClauses().stream().anyMatch(catchClause -> hasAwait(catchClause.getBody().getStatements())) ||
                                        statement.asTryStmt().getFinallyBlock().isPresent() && hasAwait(statement.asTryStmt().getFinallyBlock().get().getStatements());
                            } else if (statement.isSwitchStmt()) {
                                return statement.asSwitchStmt().getEntries().stream().anyMatch(switchEntry -> hasAwait(switchEntry.getStatements()));
                            }
                            return false;
                        }
                );
    }

    private boolean hasAwait(ReturnStmt returnStmt) {
        return returnStmt.getExpression().stream()
                .anyMatch(expression ->
                        expression.isMethodCallExpr() &&
                                expression.asMethodCallExpr().getNameAsString().equals("await")
                );
    }

    private boolean ifStmtHasReturnStmt(IfStmt ifStmt) {
        if (ifStmt.getThenStmt().isReturnStmt()) {
            return true;
        } else if (ifStmt.getThenStmt().isBlockStmt()) {
            return hasReturnStmt(ifStmt.getThenStmt().asBlockStmt().getStatements());
        }

        if (ifStmt.getElseStmt().isPresent()) {
            if (ifStmt.getElseStmt().get().isReturnStmt()) {
                return true;
            } else if (ifStmt.getElseStmt().get().isBlockStmt()) {
                return hasReturnStmt(ifStmt.getElseStmt().get().asBlockStmt().getStatements());
            } else if (ifStmt.getElseStmt().get().isIfStmt()) {
                return ifStmtHasReturnStmt(ifStmt.getElseStmt().get().asIfStmt());
            }
        }
        return false;
    }

    private boolean ifStmtHasAwait(IfStmt ifStmt) {
        if (ifStmt.getThenStmt().isReturnStmt()) {
            return hasAwait(ifStmt.getThenStmt().asReturnStmt());
        } else if (ifStmt.getThenStmt().isBlockStmt()) {
            return hasAwait(ifStmt.getThenStmt().asBlockStmt().getStatements());
        }

        if (ifStmt.getElseStmt().isPresent()) {
            if (ifStmt.getElseStmt().get().isReturnStmt()) {
                return hasAwait(ifStmt.getElseStmt().get().asReturnStmt());
            } else if (ifStmt.getElseStmt().get().isBlockStmt()) {
                return hasAwait(ifStmt.getElseStmt().get().asBlockStmt().getStatements());
            } else if (ifStmt.getElseStmt().get().isIfStmt()) {
                return ifStmtHasAwait(ifStmt.getElseStmt().get().asIfStmt());
            }
        }
        return false;
    }

    private void buildIfStmt(Node parentNode, ClassOrInterfaceDeclaration componentClassDeclaration, List<Statement> statementNodeList, int i, IfStmt ifStmt) {
        for (Statement statement : statementNodeList) {
            if (!statement.hasParentNode()) {
                statement.setParentNode(parentNode);
            }
        }
        if (ifStmt.getThenStmt().isBlockStmt()) {
            if (hasAwait(ifStmt.getThenStmt().asBlockStmt().getStatements()) && !hasReturnStmt(ifStmt.getThenStmt().asBlockStmt().getStatements())) {
                ifStmt.getThenStmt().asBlockStmt().setStatements(buildAsyncMethodBody(ifStmt.getThenStmt().asBlockStmt().getParentNode().orElse(null), componentClassDeclaration, Stream.concat(ifStmt.getThenStmt().asBlockStmt().getStatements().stream(), statementNodeList.subList(i + 1, statementNodeList.size()).stream()).collect(Collectors.toList())));
            } else {
                ifStmt.getThenStmt().asBlockStmt().setStatements(buildAsyncMethodBody(ifStmt.getThenStmt().asBlockStmt().getParentNode().orElse(null), componentClassDeclaration, ifStmt.getThenStmt().asBlockStmt().getStatements()));
            }
        } else if (ifStmt.getThenStmt().isReturnStmt()) {
            buildAsyncReturnExpression(componentClassDeclaration, ifStmt.getThenStmt().asReturnStmt())
                    .ifPresent(expression -> ifStmt.getThenStmt().asReturnStmt().setExpression(expression));
        }

        if (ifStmt.getElseStmt().isPresent()) {
            if (ifStmt.getElseStmt().get().isIfStmt()) {
                buildIfStmt(parentNode, componentClassDeclaration, statementNodeList, i, ifStmt.getElseStmt().get().asIfStmt());
            } else if (ifStmt.getElseStmt().get().isBlockStmt()) {
                if (hasAwait(ifStmt.getElseStmt().get().asBlockStmt().getStatements()) && !hasReturnStmt(ifStmt.getElseStmt().get().asBlockStmt().getStatements())) {
                    ifStmt.getElseStmt().get().asBlockStmt().setStatements(buildAsyncMethodBody(ifStmt.getElseStmt().get().asBlockStmt().getParentNode().orElse(null), componentClassDeclaration, Stream.concat(ifStmt.getElseStmt().get().asBlockStmt().getStatements().stream(), statementNodeList.subList(i + 1, statementNodeList.size()).stream()).collect(Collectors.toList())));
                } else {
                    ifStmt.getElseStmt().get().asBlockStmt().setStatements(buildAsyncMethodBody(ifStmt.getElseStmt().get().asBlockStmt().getParentNode().orElse(null), componentClassDeclaration, ifStmt.getElseStmt().get().asBlockStmt().getStatements()));
                }
            } else if (ifStmt.getElseStmt().get().isReturnStmt()) {
                buildAsyncReturnExpression(componentClassDeclaration, ifStmt.getElseStmt().get().asReturnStmt())
                        .ifPresent(expression -> ifStmt.getElseStmt().get().asReturnStmt().setExpression(expression));
            }
        }
    }

    private Optional<Expression> buildAsyncReturnExpression(ClassOrInterfaceDeclaration componentClassDeclaration, ReturnStmt returnStmt) {
        return returnStmt.getExpression()
                .map(expression -> {
                            if (expression.isMethodCallExpr()) {
                                if (expression.asMethodCallExpr().getNameAsString().equals("await")) {
                                    return expression.asMethodCallExpr().getArgument(0).asMethodCallExpr().clone();
                                }
                                if (expression.asMethodCallExpr().getScope().isPresent() && expression.asMethodCallExpr().getScope().get().isNameExpr()) {
                                    if (expression.asMethodCallExpr().getScope().get().asNameExpr().getNameAsString().equals(Mono.class.getSimpleName())) {
                                        return expression.clone();
                                    } else {
                                        return new MethodCallExpr("just")
                                                .addArgument(expression.clone())
                                                .setScope(new NameExpr(Mono.class.getSimpleName()));
                                    }
                                }
                                String methodDeclarationReturnTypeName = processorManager.resolveMethodDeclarationReturnTypeQualifiedName(componentClassDeclaration, expression.asMethodCallExpr());
                                if (methodDeclarationReturnTypeName.equals(Mono.class.getCanonicalName())) {
                                    return expression;
                                }
                            }
                            return new MethodCallExpr("just")
                                    .addArgument(expression.clone())
                                    .setScope(new NameExpr(Mono.class.getSimpleName()));
                        }
                );
    }

    private Optional<MethodDeclaration> buildAsyncMethodDeclaration(ClassOrInterfaceDeclaration componentClassDeclaration) {
        CompilationUnit asyncableCompilationUnit = processorManager.getCompilationUnitOrError(Asyncable.class.getCanonicalName());
        ClassOrInterfaceDeclaration asyncableClassOrInterfaceDeclaration = processorManager.getPublicClassOrInterfaceDeclarationOrError(asyncableCompilationUnit);
        MethodDeclaration asyncMethodDeclaration = asyncableClassOrInterfaceDeclaration.getMethodsByName("async").get(0);

        List<MethodDeclaration> asyncMethodDeclarationList = componentClassDeclaration.getMethods().stream()
                .filter(methodDeclaration -> methodDeclaration.isAnnotationPresent(Async.class))
                .collect(Collectors.toList());

        if (asyncMethodDeclarationList.isEmpty()) {
            return Optional.empty();
        }

        SwitchExpr switchExpr = new SwitchExpr()
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
                                                                            new AssignExpr(
                                                                                    new NameExpr("result"),
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
                                                                                            .setType(asyncMethodDeclaration.getType()),
                                                                                    AssignExpr.Operator.ASSIGN
                                                                            )
                                                                    )
                                                                    .addStatement(new BreakStmt());
                                                        }
                                                ),
                                        Stream.of(new SwitchEntry().addStatement(new AssignExpr(new NameExpr("result"), new MethodCallExpr("empty").setScope(new NameExpr(Mono.class.getSimpleName())), AssignExpr.Operator.ASSIGN)))
                                )
                                .collect(Collectors.toCollection(NodeList::new))
                );

        return Optional.of(
                new MethodDeclaration().setName("async")
                        .setModifiers(Modifier.Keyword.PUBLIC)
                        .setType(asyncMethodDeclaration.getType())
                        .setParameters(asyncMethodDeclaration.getParameters())
                        .setTypeParameters(asyncMethodDeclaration.getTypeParameters())
                        .addAnnotation(Override.class)
                        .setBody(
                                new BlockStmt()
                                        .addStatement(new VariableDeclarationExpr().addVariable(new VariableDeclarator(asyncMethodDeclaration.getType(), "result")))
                                        .addStatement(switchExpr)
                                        .addStatement(new ReturnStmt(new NameExpr("result")))
                        )
        );
    }
}
