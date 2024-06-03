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
        componentCompilationUnit.clone()
                .addImport(Mono.class)
                .addImport(Flux.class)
                .getTypes().stream()
                .filter(BodyDeclaration::isClassOrInterfaceDeclaration)
                .map(BodyDeclaration::asClassOrInterfaceDeclaration)
                .forEach(classOrInterfaceDeclaration -> {
                            classOrInterfaceDeclaration.getMethods().stream()
                                    .filter(methodDeclaration -> methodDeclaration.isAnnotationPresent(Async.class))
                                    .forEach(methodDeclaration ->
                                            methodDeclaration.getBody()
                                                    .ifPresent(methodBody -> {
                                                                componentProxyCompilationUnit.addImport(Mono.class);
                                                                componentProxyCompilationUnit.addImport(Flux.class);
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
                                                                        .setType(new ClassOrInterfaceType().setName(Mono.class.getSimpleName()).setTypeArguments(methodDeclaration.getType()));
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
                                                                asyncMethodDeclaration.createBody().setStatements(buildAsyncStatements(methodBody.getStatements(), defaultIfEmpty));
                                                            }
                                                    )
                                    );
                            buildAsyncMethodDeclaration(componentClassDeclaration).ifPresent(componentProxyClassDeclaration::addMember);
                        }
                );
    }

    protected NodeList<Statement> buildAsyncStatements(List<Statement> statementNodeList, String defaultIfEmpty) {
        boolean hasReturnStmt = hasReturnStmt(statementNodeList);
        boolean hasAwait = hasAwait(statementNodeList);
        NodeList<Statement> asyncStatements = new NodeList<>();
        for (int i = 0; i < statementNodeList.size(); i++) {
            Statement statement = statementNodeList.get(i);
            List<Statement> lastStatementList = statementNodeList.subList(i + 1, statementNodeList.size());
            if (statement.isExpressionStmt() &&
                    statement.asExpressionStmt().getExpression().isMethodCallExpr() &&
                    statement.asExpressionStmt().getExpression().asMethodCallExpr().getNameAsString().equals("await")
            ) {
                MethodCallExpr methodCallExpr = statement.asExpressionStmt().getExpression().asMethodCallExpr().getArgument(0).asMethodCallExpr();
                String methodDeclarationReturnTypeName = processorManager.resolveMethodDeclarationReturnTypeQualifiedName(methodCallExpr);
                if (methodCallExpr.getScope().isPresent() && processorManager.calculateType(methodCallExpr.getScope().get()).asReferenceType().getQualifiedName().equals(Provider.class.getCanonicalName()) ||
                        methodDeclarationReturnTypeName.equals(Mono.class.getCanonicalName())) {
                    if (lastStatementList.isEmpty()) {
                        MethodCallExpr then = new MethodCallExpr("then")
                                .setScope(methodCallExpr);
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(then);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(then));
                        }

                    } else if (hasReturnStmt) {
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
                    } else if (hasAwait) {
                        MethodCallExpr thenEmpty = new MethodCallExpr("thenEmpty")
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
                                    .setScope(thenEmpty);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(thenEmpty));
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
                        MethodCallExpr then = new MethodCallExpr("then")
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
                    } else if (hasReturnStmt) {
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
                    } else if (hasAwait) {
                        MethodCallExpr thenEmpty = new MethodCallExpr("thenEmpty")
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
                                    .setScope(thenEmpty);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(thenEmpty));
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
                                .setScope(
                                        new MethodCallExpr("collectList")
                                                .setScope(methodCallExpr)
                                );
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
                        MethodCallExpr then = new MethodCallExpr("then")
                                .setScope(asyncMethodCallExpr);
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(then);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(then));
                        }
                    } else if (hasReturnStmt) {
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
                    } else if (hasAwait) {
                        MethodCallExpr thenEmpty = new MethodCallExpr("thenEmpty")
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
                                    .setScope(thenEmpty);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(thenEmpty));
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
                    statement.asExpressionStmt().getExpression().isVariableDeclarationExpr() &&
                    statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariables().size() == 1 &&
                    statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0).getInitializer().isPresent() &&
                    statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0).getInitializer().get().isMethodCallExpr() &&
                    statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0).getInitializer().get().asMethodCallExpr().getNameAsString().equals("await")
            ) {
                VariableDeclarator variableDeclarator = statement.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariable(0);
                MethodCallExpr methodCallExpr = variableDeclarator.getInitializer().get().asMethodCallExpr().getArgument(0).asMethodCallExpr();
                String methodDeclarationReturnTypeName = processorManager.resolveMethodDeclarationReturnTypeQualifiedName(methodCallExpr);
                if (methodCallExpr.getScope().isPresent() && processorManager.calculateType(methodCallExpr.getScope().get()).asReferenceType().getQualifiedName().equals(Provider.class.getCanonicalName()) ||
                        methodDeclarationReturnTypeName.equals(Mono.class.getCanonicalName())) {
                    if (hasReturnStmt) {
                        MethodCallExpr flatMap = new MethodCallExpr("flatMap")
                                .addArgument(
                                        new LambdaExpr()
                                                .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                )
                                .setScope(methodCallExpr);
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(flatMap);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(flatMap));
                        }
                    } else if (hasAwait) {
                        MethodCallExpr flatMap = new MethodCallExpr("flatMap")
                                .addArgument(
                                        new LambdaExpr()
                                                .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                )
                                .setScope(methodCallExpr);
                        MethodCallExpr then = new MethodCallExpr("then").setScope(flatMap);
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(then);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(then));
                        }
                    } else {
                        MethodCallExpr doOnSuccess = new MethodCallExpr("doOnSuccess")
                                .addArgument(
                                        new LambdaExpr()
                                                .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                )
                                .setScope(methodCallExpr);
                        MethodCallExpr then = new MethodCallExpr("then").setScope(doOnSuccess);
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
                } else if (methodDeclarationReturnTypeName.equals(Flux.class.getCanonicalName())) {
                    if (hasReturnStmt) {
                        MethodCallExpr flatMap = new MethodCallExpr("flatMap")
                                .addArgument(
                                        new LambdaExpr()
                                                .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                )
                                .setScope(
                                        new MethodCallExpr("collectList")
                                                .setScope(methodCallExpr)
                                );
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(flatMap);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(flatMap));
                        }
                    } else if (hasAwait) {
                        MethodCallExpr flatMap = new MethodCallExpr("flatMap")
                                .addArgument(
                                        new LambdaExpr()
                                                .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                )
                                .setScope(
                                        new MethodCallExpr("collectList")
                                                .setScope(methodCallExpr)
                                );
                        MethodCallExpr then = new MethodCallExpr("then").setScope(flatMap);
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(then);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(then));
                        }
                    } else {
                        MethodCallExpr doOnSuccess = new MethodCallExpr("doOnSuccess")
                                .addArgument(
                                        new LambdaExpr()
                                                .addParameter(new Parameter(new UnknownType(), variableDeclarator.getName()))
                                                .setBody(new BlockStmt(buildAsyncStatements(lastStatementList, defaultIfEmpty)))
                                )
                                .setScope(
                                        new MethodCallExpr("collectList")
                                                .setScope(methodCallExpr)
                                );
                        MethodCallExpr then = new MethodCallExpr("then").setScope(doOnSuccess);
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

                    if (hasReturnStmt) {
                        MethodCallExpr flatMap = new MethodCallExpr("flatMap")
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
                                                                .setBody(new ExpressionStmt(new CastExpr().setType(methodDeclarationReturnTypeName).setExpression(new NameExpr("result"))))
                                                )
                                                .setScope(asyncMethodCallExpr)
                                );
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(flatMap);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(flatMap));
                        }
                    } else if (hasAwait) {
                        MethodCallExpr flatMap = new MethodCallExpr("flatMap")
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
                                                                .setBody(new ExpressionStmt(new CastExpr().setType(methodDeclarationReturnTypeName).setExpression(new NameExpr("result"))))
                                                )
                                                .setScope(asyncMethodCallExpr)
                                );
                        MethodCallExpr then = new MethodCallExpr("then").setScope(flatMap);
                        if (defaultIfEmpty != null) {
                            MethodCallExpr defaultIfEmptyMethod = new MethodCallExpr("defaultIfEmpty")
                                    .addArgument(new NameExpr(defaultIfEmpty))
                                    .setScope(then);
                            asyncStatements.add(new ReturnStmt(defaultIfEmptyMethod));
                        } else {
                            asyncStatements.add(new ReturnStmt(then));
                        }
                    } else {
                        MethodCallExpr doOnSuccess = new MethodCallExpr("doOnSuccess")
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
                                                                .setBody(new ExpressionStmt(new CastExpr().setType(methodDeclarationReturnTypeName).setExpression(new NameExpr("result"))))
                                                )
                                                .setScope(asyncMethodCallExpr)
                                );
                        MethodCallExpr then = new MethodCallExpr("then").setScope(doOnSuccess);
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
                }
            } else if (statement.isBlockStmt()) {
                if (hasAwait(statement.asBlockStmt().getStatements()) && !hasReturnStmt(statement.asBlockStmt().getStatements())) {
                    statement.asBlockStmt().setStatements(buildAsyncStatements(Stream.concat(statement.asBlockStmt().getStatements().stream(), lastStatementList.stream()).collect(Collectors.toList()), defaultIfEmpty));
                } else {
                    statement.asBlockStmt().setStatements(buildAsyncStatements(statement.asBlockStmt().getStatements(), defaultIfEmpty));
                }
                asyncStatements.add(statement.clone());
            } else if (statement.isIfStmt()) {
                buildIfStmt(statementNodeList, i, statement.asIfStmt(), defaultIfEmpty);
                asyncStatements.add(statement.clone());
            } else if (statement.isForStmt()) {
                if (statement.asForStmt().getBody().isBlockStmt()) {
                    if (hasAwait(statement.asForStmt().getBody().asBlockStmt().getStatements()) && !hasReturnStmt(statement.asForStmt().getBody().asBlockStmt().getStatements())) {
                        MethodCallExpr flatMap = new MethodCallExpr("flatMap")
                                .addArgument(
                                        new LambdaExpr()
                                                .addParameter(new Parameter(new UnknownType(), statement.asForStmt().getInitialization().get(0).asVariableDeclarationExpr().getVariable(0).getNameAsString()))
                                                .setBody(new BlockStmt(buildAsyncStatements(statement.asForStmt().getBody().asBlockStmt().getStatements(), defaultIfEmpty)))
                                )
                                .setScope(
                                        new MethodCallExpr("range")
                                                .addArgument(statement.asForStmt().getInitialization().get(0).asVariableDeclarationExpr().getVariable(0).getInitializer().get().asAssignExpr().getValue())
                                                .addArgument(statement.asForStmt().getCompare().get().asBinaryExpr().getRight())
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
                        statement.asForStmt().getBody().asBlockStmt().setStatements(buildAsyncStatements(statement.asForStmt().getBody().asBlockStmt().getStatements(), defaultIfEmpty));
                    }
                } else if (statement.asForStmt().getBody().isReturnStmt()) {
                    buildAsyncReturnExpression(statement.asForStmt().getBody().asReturnStmt())
                            .ifPresent(expression -> statement.asForStmt().getBody().asReturnStmt().setExpression(expression));
                }
                asyncStatements.add(statement.clone());
            } else if (statement.isForEachStmt()) {
                if (statement.asForEachStmt().getBody().isBlockStmt()) {
                    if (hasAwait(statement.asForEachStmt().getBody().asBlockStmt().getStatements()) && !hasReturnStmt(statement.asForEachStmt().getBody().asBlockStmt().getStatements())) {
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
                                .setScope(flatMap);
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
                        statement.asForEachStmt().getBody().asBlockStmt().setStatements(buildAsyncStatements(statement.asForEachStmt().getBody().asBlockStmt().getStatements(), defaultIfEmpty));
                    }
                } else if (statement.asForEachStmt().getBody().isReturnStmt()) {
                    buildAsyncReturnExpression(statement.asForEachStmt().getBody().asReturnStmt())
                            .ifPresent(expression -> statement.asForEachStmt().getBody().asReturnStmt().setExpression(expression));
                }
                asyncStatements.add(statement.clone());
            } else if (statement.isTryStmt()) {
                if (hasAwait(statement.asTryStmt().getTryBlock().getStatements()) && !hasReturnStmt(statement.asTryStmt().getTryBlock().getStatements())) {
                    statement.asTryStmt().getTryBlock().setStatements(buildAsyncStatements(Stream.concat(statement.asTryStmt().getTryBlock().getStatements().stream(), lastStatementList.stream()).collect(Collectors.toList()), defaultIfEmpty));
                } else {
                    statement.asTryStmt().getTryBlock().setStatements(buildAsyncStatements(statement.asTryStmt().getTryBlock().getStatements(), defaultIfEmpty));
                }
                for (CatchClause catchClause : statement.asTryStmt().getCatchClauses()) {
                    if (hasAwait(catchClause.getBody().getStatements()) && !hasReturnStmt(catchClause.getBody().getStatements())) {
                        catchClause.getBody().setStatements(buildAsyncStatements(Stream.concat(catchClause.getBody().getStatements().stream(), lastStatementList.stream()).collect(Collectors.toList()), defaultIfEmpty));
                    } else {
                        catchClause.getBody().setStatements(buildAsyncStatements(catchClause.getBody().getStatements(), defaultIfEmpty));
                    }
                }
                if (statement.asTryStmt().getFinallyBlock().isPresent()) {
                    if (hasAwait(statement.asTryStmt().getFinallyBlock().get().getStatements()) && !hasReturnStmt(statement.asTryStmt().getFinallyBlock().get().getStatements())) {
                        statement.asTryStmt().getFinallyBlock().get().setStatements(buildAsyncStatements(Stream.concat(statement.asTryStmt().getFinallyBlock().get().getStatements().stream(), lastStatementList.stream()).collect(Collectors.toList()), defaultIfEmpty));
                    } else {
                        statement.asTryStmt().getFinallyBlock().get().setStatements(buildAsyncStatements(statement.asTryStmt().getFinallyBlock().get().getStatements(), defaultIfEmpty));
                    }
                }
                asyncStatements.add(statement.clone());
            } else if (statement.isSwitchStmt()) {
                for (SwitchEntry switchEntry : statement.asSwitchStmt().getEntries()) {
                    if (hasAwait(switchEntry.getStatements()) && !hasReturnStmt(switchEntry.getStatements())) {
                        switchEntry.setStatements(buildAsyncStatements(Stream.concat(switchEntry.getStatements().stream(), lastStatementList.stream()).collect(Collectors.toList()), defaultIfEmpty));
                    } else {
                        switchEntry.setStatements(buildAsyncStatements(switchEntry.getStatements(), defaultIfEmpty));
                    }
                }
                asyncStatements.add(statement.clone());
            } else if (statement.isReturnStmt()) {
                buildAsyncReturnExpression(statement.asReturnStmt())
                        .ifPresent(expression -> statement.asReturnStmt().setExpression(expression));
                asyncStatements.add(statement.clone());
            } else {
                asyncStatements.add(statement.clone());
            }
        }
        return asyncStatements;
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
                            } else if (statement.isForStmt() && statement.asForStmt().getBody().isBlockStmt()) {
                                return hasReturnStmt(statement.asForStmt().getBody().asBlockStmt().getStatements());
                            } else if (statement.isForEachStmt() && statement.asForEachStmt().getBody().isBlockStmt()) {
                                return hasReturnStmt(statement.asForEachStmt().getBody().asBlockStmt().getStatements());
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

    private void buildIfStmt(List<Statement> statementNodeList, int i, IfStmt ifStmt, String defaultIfEmpty) {
        List<Statement> lastStatementList = statementNodeList.subList(i + 1, statementNodeList.size());
        if (ifStmt.getThenStmt().isBlockStmt()) {
            if (hasAwait(ifStmt.getThenStmt().asBlockStmt().getStatements()) && !hasReturnStmt(ifStmt.getThenStmt().asBlockStmt().getStatements())) {
                ifStmt.getThenStmt().asBlockStmt().setStatements(buildAsyncStatements(Stream.concat(ifStmt.getThenStmt().asBlockStmt().getStatements().stream(), lastStatementList.stream().map(this::cloneWithParent)).collect(Collectors.toList()), defaultIfEmpty));
            } else {
                ifStmt.getThenStmt().asBlockStmt().setStatements(buildAsyncStatements(ifStmt.getThenStmt().asBlockStmt().getStatements(), defaultIfEmpty));
            }
        } else if (ifStmt.getThenStmt().isReturnStmt()) {
            buildAsyncReturnExpression(ifStmt.getThenStmt().asReturnStmt())
                    .ifPresent(expression -> ifStmt.getThenStmt().asReturnStmt().setExpression(expression));
        }

        if (ifStmt.getElseStmt().isPresent()) {
            if (ifStmt.getElseStmt().get().isIfStmt()) {
                buildIfStmt(statementNodeList, i, ifStmt.getElseStmt().get().asIfStmt(), defaultIfEmpty);
            } else if (ifStmt.getElseStmt().get().isBlockStmt()) {
                if (hasAwait(ifStmt.getElseStmt().get().asBlockStmt().getStatements()) && !hasReturnStmt(ifStmt.getElseStmt().get().asBlockStmt().getStatements())) {
                    ifStmt.getElseStmt().get().asBlockStmt().setStatements(buildAsyncStatements(Stream.concat(ifStmt.getElseStmt().get().asBlockStmt().getStatements().stream(), lastStatementList.stream().map(this::cloneWithParent)).collect(Collectors.toList()), defaultIfEmpty));
                } else {
                    ifStmt.getElseStmt().get().asBlockStmt().setStatements(buildAsyncStatements(ifStmt.getElseStmt().get().asBlockStmt().getStatements(), defaultIfEmpty));
                }
            } else if (ifStmt.getElseStmt().get().isReturnStmt()) {
                buildAsyncReturnExpression(ifStmt.getElseStmt().get().asReturnStmt())
                        .ifPresent(expression -> ifStmt.getElseStmt().get().asReturnStmt().setExpression(expression));
            }
        } else {
            if ((ifStmt.getThenStmt().isReturnStmt() ||
                    ifStmt.getThenStmt().isBlockStmt() && hasReturnStmt(ifStmt.getThenStmt().asBlockStmt().getStatements())) &&
                    !hasReturnStmt(lastStatementList)) {
                ifStmt.setElseStmt(
                        new BlockStmt()
                                .addStatement(
                                        new ReturnStmt(
                                                new MethodCallExpr("empty")
                                                        .setScope(new NameExpr(Mono.class.getSimpleName()))
                                        )
                                )
                );
            }
        }
    }

    private Statement cloneWithParent(Statement statement) {
        Statement cloned = statement.clone();
        cloned.setParentNode(statement.getParentNodeForChildren());
        return cloned;
    }

    private Optional<Expression> buildAsyncReturnExpression(ReturnStmt returnStmt) {
        return returnStmt.getExpression()
                .map(expression -> {
                            if (expression.isMethodCallExpr()) {
                                if (expression.asMethodCallExpr().getNameAsString().equals("await")) {
                                    return expression.asMethodCallExpr().getArgument(0).asMethodCallExpr().clone();
                                }
                                String methodDeclarationReturnTypeName = processorManager.resolveMethodDeclarationReturnTypeQualifiedName(expression.asMethodCallExpr());
                                if (methodDeclarationReturnTypeName.equals(Mono.class.getCanonicalName())) {
                                    return expression.clone();
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
