package io.nozdormu.inject.processor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import io.nozdormu.common.ProcessorManager;

public interface ComponentProxyProcessor {

    default void init(ProcessorManager processorManager) {
    }

    default void inProcess() {
    }

    default boolean processComponentProxy(CompilationUnit componentCompilationUnit,
                                       ClassOrInterfaceDeclaration componentClassDeclaration,
                                       CompilationUnit componentProxyCompilationUnit,
                                       ClassOrInterfaceDeclaration componentProxyClassDeclaration) {
        return false;
    }
}
