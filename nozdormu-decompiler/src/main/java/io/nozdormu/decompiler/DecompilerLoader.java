package io.nozdormu.decompiler;

import org.jd.core.v1.api.loader.Loader;
import org.jd.core.v1.api.loader.LoaderException;
import org.tinylog.Logger;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class DecompilerLoader implements Loader {

    private final HashMap<String, byte[]> classBytesCache = new HashMap<>();

    private ClassLoader classLoader;

    public DecompilerLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public boolean canLoad(String className) {
        if (!classExists(className)) {
            return false;
        }
        String decompilerClassName = getDecompilerClassName(className);
        try {
            return classBytesCache.containsKey(decompilerClassName + ".class") || loadAndCache(decompilerClassName);
        } catch (LoaderException e) {
            Logger.warn(e);
            return false;
        }
    }

    @Override
    public byte[] load(String className) throws LoaderException {
        String decompilerClassName = getDecompilerClassName(className);
        if (classBytesCache.containsKey(decompilerClassName + ".class") || loadAndCache(decompilerClassName)) {
            return classBytesCache.get(decompilerClassName + ".class");
        }
        throw new LoaderException(decompilerClassName + " not find");
    }

    public boolean classExists(String className) {
        try {
            Class.forName(className, false, classLoader);
            return true;
        } catch (ClassNotFoundException e) {
            int i = className.lastIndexOf(".");
            if (i != -1) {
                String nestedClassName = className.substring(0, i) + "$" + className.substring(i + 1);
                return classExists(nestedClassName);
            } else {
                return false;
            }
        }
    }

    public String getDecompilerClassName(String className) {
        try {
            return Class.forName(className, false, classLoader).getName();
        } catch (ClassNotFoundException e) {
            int i = className.lastIndexOf(".");
            String nestedClassName = className.substring(0, i) + "$" + className.substring(i + 1);
            return getDecompilerClassName(nestedClassName);
        }
    }

    private boolean loadAndCache(String compileClassName) throws LoaderException {
        byte[] buffer = new byte[1024 * 2];
        try {
            Class<?> compileClass = Class.forName(compileClassName, false, classLoader);
            CodeSource codeSource = compileClass.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                InputStream inputStream = new FileInputStream(Paths.get(codeSource.getLocation().toURI()).toFile());
                ZipInputStream zipInputStream = new ZipInputStream(inputStream);
                ZipEntry zipEntry = zipInputStream.getNextEntry();
                while (zipEntry != null) {
                    if (!zipEntry.isDirectory()) {
                        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                        int read = zipInputStream.read(buffer);

                        while (read > 0) {
                            byteArrayOutputStream.write(buffer, 0, read);
                            read = zipInputStream.read(buffer);
                        }
                        classBytesCache.put(zipEntry.getName().replace("/", "."), byteArrayOutputStream.toByteArray());
                    }
                    zipEntry = zipInputStream.getNextEntry();
                }
                zipInputStream.closeEntry();
                if (classBytesCache.containsKey(compileClassName + ".class")) {
                    return true;
                }
            }
        } catch (IOException | URISyntaxException | ClassNotFoundException e) {
            Logger.warn(e);
            throw new LoaderException(e);
        }
        return false;
    }
}
