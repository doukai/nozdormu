package io.nozdormu.config.processor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.resolution.declarations.ResolvedDeclaration;
import com.google.auto.service.AutoService;
import io.nozdormu.common.ProcessorManager;
import io.nozdormu.inject.processor.ComponentProxyProcessor;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.stream.Collectors;

@AutoService(ComponentProxyProcessor.class)
public class ConfigComponentProcessor implements ComponentProxyProcessor {

    private ProcessorManager processorManager;

    @Override
    public void init(ProcessorManager processorManager) {
        this.processorManager = processorManager;
    }

    @Override
    public void processComponentProxy(CompilationUnit componentCompilationUnit, ClassOrInterfaceDeclaration componentClassDeclaration, CompilationUnit componentProxyCompilationUnit, ClassOrInterfaceDeclaration componentProxyClassDeclaration) {
        List<MethodDeclaration> methodDeclarationList = componentClassDeclaration.getMethods().stream()
                .filter(methodDeclaration ->
                        methodDeclaration.isAnnotationPresent(ConfigProperty.class) ||
                                isConfigPropertyFieldSetter(methodDeclaration)
                )
                .collect(Collectors.toList());

        if (!methodDeclarationList.isEmpty() && componentProxyClassDeclaration.getConstructors().isEmpty()) {
            componentProxyClassDeclaration
                    .addMember(
                            new ConstructorDeclaration()
                                    .setName(componentProxyClassDeclaration.getName())
                                    .setModifiers(Modifier.Keyword.PUBLIC)
                                    .addAnnotation(Inject.class)
                                    .setBody(new BlockStmt())
                    );
            componentProxyCompilationUnit.addImport(Inject.class);
        }
        componentProxyClassDeclaration.getConstructors()
                .forEach(constructorDeclaration ->
                        methodDeclarationList.forEach(methodDeclaration -> {
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
                .map(assignExpr -> processorManager.getResolvedDeclaration(assignExpr.getTarget().asFieldAccessExpr()))
                .filter(ResolvedDeclaration::isField)
                .flatMap(resolvedValueDeclaration -> resolvedValueDeclaration.asField().toAst().stream())
                .map(node -> (FieldDeclaration) node)
                .anyMatch(fieldDeclaration -> fieldDeclaration.isAnnotationPresent(ConfigProperty.class));
    }
}
