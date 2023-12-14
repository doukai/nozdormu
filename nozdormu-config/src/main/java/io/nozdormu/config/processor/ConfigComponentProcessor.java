package io.nozdormu.config.processor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.resolution.declarations.ResolvedDeclaration;
import com.google.auto.service.AutoService;
import io.nozdormu.inject.processor.ComponentProxyProcessor;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.stream.Collectors;

@AutoService(ComponentProxyProcessor.class)
public class ConfigComponentProcessor implements ComponentProxyProcessor {

    @Override
    public void processComponentProxy(CompilationUnit componentCompilationUnit, ClassOrInterfaceDeclaration componentClassDeclaration, CompilationUnit componentProxyCompilationUnit, ClassOrInterfaceDeclaration componentProxyClassDeclaration) {
        componentProxyClassDeclaration.getConstructors()
                .forEach(constructorDeclaration ->
                        componentClassDeclaration.getMethods().stream()
                                .filter(methodDeclaration ->
                                        methodDeclaration.isAnnotationPresent(ConfigProperty.class) ||
                                                isConfigPropertyFieldSetter(methodDeclaration)
                                )
                                .forEach(methodDeclaration -> {
                                            methodDeclaration.getParameters().forEach(constructorDeclaration::addParameter);
                                            constructorDeclaration
                                                    .getBody()
                                                    .addStatement(
                                                            new MethodCallExpr()
                                                                    .setName(methodDeclaration.getName())
                                                                    .setArguments(
                                                                            methodDeclaration.getParameters().stream()
                                                                                    .map(NodeWithSimpleName::getNameAsExpression)
                                                                                    .collect(Collectors.toCollection(NodeList::new))
                                                                    )
                                                    );
                                        }
                                )
                );
    }

    private boolean isConfigPropertyFieldSetter(MethodDeclaration methodDeclaration) {
        return methodDeclaration.getBody().stream()
                .flatMap(blockStmt -> blockStmt.findAll(AssignExpr.class).stream())
                .filter(assignExpr -> assignExpr.getTarget().isFieldAccessExpr())
                .map(assignExpr -> assignExpr.getTarget().asFieldAccessExpr().resolve())
                .filter(ResolvedDeclaration::isField)
                .flatMap(resolvedValueDeclaration -> resolvedValueDeclaration.asField().toAst().stream())
                .map(node -> (FieldDeclaration) node)
                .anyMatch(fieldDeclaration -> fieldDeclaration.isAnnotationPresent(ConfigProperty.class));
    }
}
