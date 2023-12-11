package io.nozdormu.decompiler;

import io.nozdormu.spi.decompiler.TypeElementDecompiler;
import org.jd.core.v1.ClassFileToJavaSourceDecompiler;

import javax.lang.model.element.TypeElement;

public class JDDecompiler implements TypeElementDecompiler {

    private final ClassFileToJavaSourceDecompiler decompiler;
    private final DecompilerLoader decompilerLoader;

    public JDDecompiler(ClassLoader classLoader) {
        this.decompiler = new ClassFileToJavaSourceDecompiler();
        this.decompilerLoader = new DecompilerLoader(classLoader);
    }

    @Override
    public String decompile(TypeElement typeElement) {
        try {
            DecompilerPrinter decompilerPrinter = new DecompilerPrinter();
            decompiler.decompile(decompilerLoader, decompilerPrinter, typeElement.asType().toString());
            return "package " + typeElement.getEnclosingElement().asType().toString() + ";" + System.lineSeparator() + decompilerPrinter;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}