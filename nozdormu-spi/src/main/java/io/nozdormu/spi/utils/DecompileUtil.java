package io.nozdormu.spi.utils;

public final class DecompileUtil {

    public static String getDecompileClassName(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader).getName();
        } catch (ClassNotFoundException e) {
            int i = className.lastIndexOf(".");
            String nestedClassName = className.substring(0, i);
            return getDecompileClassName(nestedClassName, classLoader);
        }
    }
}
