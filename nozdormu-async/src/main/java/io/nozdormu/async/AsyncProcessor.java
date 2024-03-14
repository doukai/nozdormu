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
        NodeList<Statement> statements = new NodeList<>();
        for (int i = 0; i < statementNodeList.size(); i++) {
            Statement statement = statementNodeList.get(i);
            if (statement.isExpressionStmt() &&
                    statement.asExpressionStmt().getExpression().isVariableDeclarationExpr() &&
                    statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariables().size() == 1 &&
                    statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0).getInitializer().isPresent() &&
                    statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0).getInitializer().get().isMethodCallExpr() &&
                    statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0).getInitializer().get().asMethodCallExpr().getNameAsString().equals("await")
            ) {
                VariableDeclarator variableDeclarator = statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0);
                MethodCallExpr methodCallExpr = variableDeclarator.getInitializer().get().asMethodCallExpr().getArgument(0).asMethodCallExpr();
                MethodDeclaration methodDeclaration = processorManager.resolveMethodDeclaration(componentClassDeclaration, methodCallExpr);
                if (methodDeclaration.getType().isClassOrInterfaceType() && processorManager.getQualifiedName(methodDeclaration.getType()).equals(Mono.class.getCanonicalName())) {
                    statements.add(
                            new ReturnStmt(
                                    new MethodCallExpr("flatMap")
                                            .addArgument(
                                                    new LambdaExpr()
                                                            .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                            .setBody(new BlockStmt(buildAsyncMethodBody(componentClassDeclaration, statementNodeList.subList(i + 1, statementNodeList.size()))))
                                            )
                                            .setScope(methodCallExpr)
                            )
                    );
                    break;
                } else if (methodDeclaration.getType().isClassOrInterfaceType() && processorManager.getQualifiedName(methodDeclaration.getType()).equals(Flux.class.getCanonicalName())) {
                    statements.add(
                            new ReturnStmt(
                                    new MethodCallExpr("flatMap")
                                            .addArgument(
                                                    new LambdaExpr()
                                                            .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                            .setBody(new BlockStmt(buildAsyncMethodBody(componentClassDeclaration, statementNodeList.subList(i + 1, statementNodeList.size()))))
                                            )
                                            .setScope(
                                                    new MethodCallExpr("collectList")
                                                            .setScope(methodCallExpr)
                                            )
                            )
                    );
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
                                            new ReturnStmt(
                                                    new MethodCallExpr("flatMap")
                                                            .addArgument(
                                                                    new LambdaExpr()
                                                                            .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                                            .setBody(new BlockStmt(buildAsyncMethodBody(componentClassDeclaration, statementNodeList.subList(i + 1, statementNodeList.size()))))
                                                            )
                                                            .setScope(new NameExpr(variableDeclarator.getNameAsString() + "Mono"))
                                            )
                                    )
                            );
                    break;
                }
            }
            if (statement.isReturnStmt() && statement.asReturnStmt().getExpression().isPresent()) {
                Expression expression = statement.asReturnStmt().getExpression().get();
                if (expression.isMethodCallExpr() && expression.asMethodCallExpr().getNameAsString().equals("await")) {
                    statements.add(
                            new ReturnStmt(
                                    expression.asMethodCallExpr().getArgument(0).asMethodCallExpr()
                            )
                    );
                } else {
                    statements.add(
                            new ReturnStmt(
                                    new MethodCallExpr("just")
                                            .addArgument(statement.asReturnStmt().getExpression().get())
                                            .setScope(new NameExpr(Mono.class.getSimpleName()))
                            )
                    );
                }
            } else {
                statements.add(statement);
            }
        }
        return statements;
    }

    Optional<MethodDeclaration> buildAsyncMethodDeclaration(ClassOrInterfaceDeclaration componentClassDeclaration) {
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
