package io.nozdormu.inject.processor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;

public interface ComponentProxyProcessor {

    void init(ProcessorManager processorManager);

    default void inProcess() {
    }

    default void processComponentProxy(CompilationUnit componentCompilationUnit,
                                       ClassOrInterfaceDeclaration componentClassDeclaration,
                                       CompilationUnit componentProxyCompilationUnit,
                                       ClassOrInterfaceDeclaration componentProxyClassDeclaration) {
    }

    default void processModuleContext(CompilationUnit moduleCompilationUnit, ClassOrInterfaceDeclaration classOrInterfaceDeclaration, BlockStmt staticInitializer) {
    }
}
