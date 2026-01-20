package io.nozdormu.spi.utils;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class QualifierUtil {

    private QualifierUtil() {
    }

    public static Map<String, Map<String, Object>> toQualifierMap(Annotation... qualifiers) {
        if (qualifiers == null || qualifiers.length == 0) {
            return Map.of();
        }
        Map<String, Map<String, Object>> qualifierMap = new HashMap<>();
        for (Annotation qualifier : qualifiers) {
            Map<String, Object> attributes = new HashMap<>();
            Arrays.stream(qualifier.annotationType().getDeclaredMethods())
                    .forEach(method -> {
                        try {
                            attributes.put(method.getName(), method.invoke(qualifier));
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException("Failed to read qualifier attributes: " + qualifier, e);
                        }
                    });
            qualifierMap.put(qualifier.annotationType().getName(), attributes);
        }
        return qualifierMap;
    }
}
