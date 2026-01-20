package io.nozdormu.decompiler.procyon;

import com.strobel.assembler.metadata.JarTypeLoader;
import com.strobel.decompiler.Decompiler;
import com.strobel.decompiler.DecompilerSettings;
import com.strobel.decompiler.PlainTextOutput;
import io.nozdormu.spi.decompiler.TypeElementDecompiler;

import javax.lang.model.element.TypeElement;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

import static io.nozdormu.spi.utils.DecompileUtil.getDecompileClassName;

public class ProcyonDecompiler implements TypeElementDecompiler {

    private final ClassLoader classLoader;

    private static final Map<String, String> DECOMPILED_CACHE = new ConcurrentHashMap<>();

    public ProcyonDecompiler(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public boolean canLoad(TypeElement typeElement) {
        String decompileClassName = getDecompileClassName(typeElement.getQualifiedName().toString(), classLoader);
        try {
            Class.forName(decompileClassName, false, classLoader);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public String decompile(TypeElement typeElement) {
        String decompileClassName = getDecompileClassName(typeElement.getQualifiedName().toString(), classLoader);
        if (DECOMPILED_CACHE.containsKey(decompileClassName)) {
            return DECOMPILED_CACHE.get(decompileClassName);
        }
        try (StringWriter writer = new StringWriter()) {
            Class<?> decompileClass = Class.forName(decompileClassName, false, classLoader);
            CodeSource codeSource = decompileClass.getProtectionDomain().getCodeSource();
            final DecompilerSettings settings = DecompilerSettings.javaDefaults();
            if (codeSource != null) {
                File file = Paths.get(codeSource.getLocation().toURI()).toFile();
                settings.setTypeLoader(new JarTypeLoader(new JarFile(file)));
            }

            Decompiler.decompile(
                    decompileClassName.replace(".", "/"),
                    new PlainTextOutput(writer),
                    settings
            );
            String java = writer.toString();
            DECOMPILED_CACHE.put(decompileClassName, java);
            return java;
        } catch (ClassNotFoundException | URISyntaxException | IOException e) {
            throw new RuntimeException(e);
        }
    }
}
