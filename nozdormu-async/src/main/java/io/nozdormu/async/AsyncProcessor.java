package io.nozdormu.async;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
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
                                MethodDeclaration asyncMethodDeclaration = new MethodDeclaration()
                                        .setName(asyncMethodName)
                                        .setModifiers(methodDeclaration.getModifiers())
                                        .setParameters(methodDeclaration.getParameters())
                                        .setType(new ClassOrInterfaceType().setName(Mono.class.getSimpleName()).setTypeArguments(methodDeclaration.getType()))
                                        .setBody(new BlockStmt(buildAsyncMethodBody(componentClassDeclaration, blockStmt.getStatements())));
                                componentProxyClassDeclaration.addMember(asyncMethodDeclaration);
                            }
                        }
                );
        buildAsyncMethodDeclaration(componentClassDeclaration).ifPresent(componentProxyClassDeclaration::addMember);
    }

    protected NodeList<Statement> buildAsyncMethodBody(ClassOrInterfaceDeclaration componentClassDeclaration, List<Statement> statementNodeList) {
        boolean hasReturnStmt = hasReturnStmt(statementNodeList);
        NodeList<Statement> statements = new NodeList<>();
        for (int i = 0; i < statementNodeList.size(); i++) {
            Statement statement = statementNodeList.get(i);
            if (statement.isExpressionStmt() &&
                    statement.asExpressionStmt().getExpression().isMethodCallExpr() &&
                    statement.asExpressionStmt().getExpression().asMethodCallExpr().getNameAsString().equals("await")
            ) {
                MethodCallExpr methodCallExpr = statement.asExpressionStmt().getExpression().asMethodCallExpr().getArgument(0).asMethodCallExpr();
                MethodDeclaration methodDeclaration = processorManager.resolveMethodDeclaration(componentClassDeclaration, methodCallExpr);
                if (methodCallExpr.getScope().isPresent() && methodCallExpr.getScope().get().calculateResolvedType().asReferenceType().getQualifiedName().equals(Provider.class.getCanonicalName()) ||
                        methodDeclaration.getType().isClassOrInterfaceType() && processorManager.getQualifiedName(methodDeclaration.getType()).equals(Mono.class.getCanonicalName())) {
                    statements.add(new ExpressionStmt(new MethodCallExpr("subscribe").setScope(methodCallExpr)));
                } else if (methodDeclaration.getType().isClassOrInterfaceType() && processorManager.getQualifiedName(methodDeclaration.getType()).equals(Flux.class.getCanonicalName())) {
                    statements.add(new ExpressionStmt(new MethodCallExpr("subscribe").setScope(methodCallExpr)));
                } else {
                    String asyncMethodName = Stream
                            .concat(
                                    Stream.of(methodCallExpr.getNameAsString() + ASYNC_METHOD_NAME_SUFFIX),
                                    processorManager.resolveMethodDeclaration(componentClassDeclaration, methodCallExpr).getParameters().stream()
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
                    statements.add(new ExpressionStmt(new MethodCallExpr("subscribe").setScope(asyncMethodCallExpr)));
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
                MethodDeclaration methodDeclaration = processorManager.resolveMethodDeclaration(componentClassDeclaration, methodCallExpr);
                if (methodCallExpr.getScope().isPresent() && methodCallExpr.getScope().get().calculateResolvedType().asReferenceType().getQualifiedName().equals(Provider.class.getCanonicalName()) ||
                        methodDeclaration.getType().isClassOrInterfaceType() && processorManager.getQualifiedName(methodDeclaration.getType()).equals(Mono.class.getCanonicalName())) {
                    MethodCallExpr flatMap = new MethodCallExpr(hasReturnStmt ? "flatMap" : "doOnSuccess")
                            .addArgument(
                                    new LambdaExpr()
                                            .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                            .setBody(new BlockStmt(buildAsyncMethodBody(componentClassDeclaration, statementNodeList.subList(i + 1, statementNodeList.size()))))
                            )
                            .setScope(methodCallExpr);

                    if (hasReturnStmt) {
                        statements.add(new ReturnStmt(flatMap));
                    } else {
                        statements.add(new ExpressionStmt(new MethodCallExpr("subscribe").setScope(flatMap)));
                    }
                    break;
                } else if (methodDeclaration.getType().isClassOrInterfaceType() && processorManager.getQualifiedName(methodDeclaration.getType()).equals(Flux.class.getCanonicalName())) {
                    MethodCallExpr flatMap = new MethodCallExpr(hasReturnStmt ? "flatMap" : "doOnSuccess")
                            .addArgument(
                                    new LambdaExpr()
                                            .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                            .setBody(new BlockStmt(buildAsyncMethodBody(componentClassDeclaration, statementNodeList.subList(i + 1, statementNodeList.size()))))
                            )
                            .setScope(
                                    new MethodCallExpr("collectList")
                                            .setScope(methodCallExpr)
                            );

                    if (hasReturnStmt) {
                        statements.add(new ReturnStmt(flatMap));
                    } else {
                        statements.add(new ExpressionStmt(new MethodCallExpr("subscribe").setScope(flatMap)));
                    }
                    break;
                } else {
                    String asyncMethodName = Stream
                            .concat(
                                    Stream.of(methodCallExpr.getNameAsString() + ASYNC_METHOD_NAME_SUFFIX),
                                    processorManager.resolveMethodDeclaration(componentClassDeclaration, methodCallExpr).getParameters().stream()
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

                    MethodCallExpr flatMap = new MethodCallExpr(hasReturnStmt ? "flatMap" : "doOnSuccess")
                            .addArgument(
                                    new LambdaExpr()
                                            .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                            .setBody(new BlockStmt(buildAsyncMethodBody(componentClassDeclaration, statementNodeList.subList(i + 1, statementNodeList.size()))))
                            )
                            .setScope(new NameExpr(variableDeclarator.getNameAsString() + "Mono"));

                    statements
                            .addAll(
                                    new NodeList<>(
                                            new ExpressionStmt(
                                                    new VariableDeclarationExpr()
                                                            .addVariable(
                                                                    new VariableDeclarator()
                                                                            .setName(variableDeclarator.getNameAsString() + "Mono")
                                                                            .setType(new ClassOrInterfaceType().setName(Mono.class.getSimpleName()).setTypeArguments(variableDeclarator.getType()))
                                                                            .setInitializer(asyncMethodCallExpr)
                                                            )
                                            ),
                                            hasReturnStmt ?
                                                    new ReturnStmt(flatMap) :
                                                    new ExpressionStmt(new MethodCallExpr("subscribe").setScope(flatMap))

                                    )
                            );
                    break;
                }
            } else if (statement.isBlockStmt()) {
                statement.asBlockStmt().setStatements(buildAsyncMethodBody(componentClassDeclaration, statement.asBlockStmt().getStatements()));
                statements.add(statement);
            } else if (statement.isIfStmt()) {
                if (statement.asIfStmt().getThenStmt().isBlockStmt()) {
                    statement.asIfStmt().getThenStmt().asBlockStmt().setStatements(buildAsyncMethodBody(componentClassDeclaration, statement.asIfStmt().getThenStmt().asBlockStmt().getStatements()));
                    statements.add(statement);
                } else if (statement.asIfStmt().getThenStmt().isReturnStmt()) {
                    buildAsyncReturnStatement(statement.asIfStmt().getThenStmt().asReturnStmt())
                            .ifPresentOrElse(
                                    statements::add,
                                    () -> statements.add(statement)
                            );
                } else if (statement.asIfStmt().getElseStmt().isPresent()) {
                    if (statement.asIfStmt().getElseStmt().get().isBlockStmt()) {
                        statement.asIfStmt().getElseStmt().get().asBlockStmt().setStatements(buildAsyncMethodBody(componentClassDeclaration, statement.asIfStmt().getElseStmt().get().asBlockStmt().getStatements()));
                        statements.add(statement);
                    } else if (statement.asIfStmt().getElseStmt().get().isReturnStmt()) {
                        buildAsyncReturnStatement(statement.asIfStmt().getElseStmt().get().asReturnStmt())
                                .ifPresentOrElse(
                                        statements::add,
                                        () -> statements.add(statement)
                                );
                    }
                }
            } else if (statement.isTryStmt()) {
                statement.asTryStmt().getTryBlock().setStatements(buildAsyncMethodBody(componentClassDeclaration, statement.asTryStmt().getTryBlock().getStatements()));
                statements.add(statement);
            } else if (statement.isSwitchStmt()) {
                statement.asSwitchStmt().getEntries()
                        .forEach(switchEntry -> switchEntry.setStatements(buildAsyncMethodBody(componentClassDeclaration, switchEntry.getStatements())));
                statements.add(statement);
            } else if (statement.isReturnStmt()) {
                buildAsyncReturnStatement(statement.asReturnStmt())
                        .ifPresentOrElse(
                                statements::add,
                                () -> statements.add(statement)
                        );
            } else {
                statements.add(statement);
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
                                if (statement.asIfStmt().getThenStmt().isReturnStmt()) {
                                    return true;
                                } else if (statement.asIfStmt().getThenStmt().isBlockStmt()) {
                                    return hasReturnStmt(statement.asIfStmt().getThenStmt().asBlockStmt().getStatements());
                                } else if (statement.asIfStmt().getElseStmt().isPresent()) {
                                    if (statement.asIfStmt().getElseStmt().get().isReturnStmt()) {
                                        return true;
                                    } else if (statement.asIfStmt().getElseStmt().get().isBlockStmt()) {
                                        return hasReturnStmt(statement.asIfStmt().getElseStmt().get().asBlockStmt().getStatements());
                                    }
                                }
                            } else if (statement.isTryStmt()) {
                                return hasReturnStmt(statement.asTryStmt().getTryBlock().getStatements());
                            } else if (statement.isSwitchStmt()) {
                                return statement.asSwitchStmt().getEntries().stream().anyMatch(switchEntry -> hasReturnStmt(switchEntry.getStatements()));
                            }
                            return false;
                        }
                );
    }

    private Optional<ReturnStmt> buildAsyncReturnStatement(ReturnStmt returnStmt) {
        return returnStmt.getExpression()
                .map(expression -> {
                            if (expression.isMethodCallExpr() && expression.asMethodCallExpr().getNameAsString().equals("await")) {
                                return new ReturnStmt(
                                        expression.asMethodCallExpr().getArgument(0).asMethodCallExpr()
                                );
                            } else {
                                return new ReturnStmt(
                                        new MethodCallExpr("just")
                                                .addArgument(expression)
                                                .setScope(new NameExpr(Mono.class.getSimpleName()))
                                );
                            }
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
