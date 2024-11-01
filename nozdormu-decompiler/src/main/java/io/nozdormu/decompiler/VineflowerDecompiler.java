package io.nozdormu.decompiler;

import io.nozdormu.spi.decompiler.TypeElementDecompiler;
import org.jetbrains.java.decompiler.api.Decompiler;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

import javax.lang.model.element.TypeElement;
import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Manifest;

import static io.nozdormu.spi.utils.DecompileUtil.getDecompileClassName;

public class VineflowerDecompiler implements TypeElementDecompiler {

    private final ClassLoader classLoader;

    private static final Map<String, String> DECOMPILED_CACHE = new HashMap<>();

    private static final IResultSaver resultSaver = new IResultSaver() {

        @Override
        public void saveFolder(String path) {
        }

        @Override
        public void copyFile(String source, String path, String entryName) {
        }

        @Override
        public void saveClassFile(String path, String qualifiedName, String entryName, String content, int[] mapping) {
            DECOMPILED_CACHE.put(qualifiedName.replace("/", "."), content);
        }

        @Override
        public void createArchive(String path, String archiveName, Manifest manifest) {
        }

        @Override
        public void saveDirEntry(String path, String archiveName, String entryName) {
        }

        @Override
        public void copyEntry(String source, String path, String archiveName, String entry) {
        }

        @Override
        public void saveClassEntry(String path, String archiveName, String qualifiedName, String entryName, String content) {
            DECOMPILED_CACHE.put(qualifiedName.replace("/", "."), content);
        }

        @Override
        public void closeArchive(String path, String archiveName) {
        }
    };

    public VineflowerDecompiler(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public boolean canLoad(TypeElement typeElement) {
        String decompileClassName = getDecompileClassName(typeElement.getQualifiedName().toString(), classLoader);
        try {
            Class.forName(decompileClassName, false, classLoader);
            return DECOMPILED_CACHE.containsKey(decompileClassName) || decompileAndCache(decompileClassName);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public String decompile(TypeElement typeElement) {
        String decompileClassName = getDecompileClassName(typeElement.getQualifiedName().toString(), classLoader);
        if (DECOMPILED_CACHE.containsKey(decompileClassName) || decompileAndCache(decompileClassName)) {
            return DECOMPILED_CACHE.get(decompileClassName);
        }
        throw new RuntimeException(decompileClassName + " not find");
    }

    public boolean decompileAndCache(String decompileClassName) {
        try {
            Class<?> decompileClass = Class.forName(decompileClassName, false, classLoader);
            CodeSource codeSource = decompileClass.getProtectionDomain().getCodeSource();
            if (codeSource == null) {
                return false;
            }
            File file = Paths.get(codeSource.getLocation().toURI()).toFile();

            Decompiler decompiler = Decompiler
                    .builder()
                    .inputs(file)
                    .output(resultSaver)
                    .build();
            decompiler.decompile();
            return DECOMPILED_CACHE.containsKey(decompileClassName);
        } catch (ClassNotFoundException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
