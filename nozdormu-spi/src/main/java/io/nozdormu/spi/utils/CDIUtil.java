package io.nozdormu.spi.utils;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Named;

import java.util.AbstractMap;
import java.util.Map;
import java.util.stream.Collectors;

public final class CDIUtil {

    public static <T> Map<String, T> getNamedInstanceMap(Instance<T> instance) {
        return instance.stream()
                .filter(beanInstance -> beanInstance.getClass().isAnnotationPresent(Named.class))
                .map(beanInstance -> new AbstractMap.SimpleEntry<>(beanInstance.getClass().getAnnotation(Named.class).value(), beanInstance))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (x, y) -> y));
    }
}
