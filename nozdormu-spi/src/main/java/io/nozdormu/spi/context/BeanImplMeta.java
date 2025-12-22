package io.nozdormu.spi.context;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;
import java.util.TreeMap;

public class BeanImplMeta extends ClassValue<TreeMap<Integer, Map<String, Object>>> {

    @Override
    protected TreeMap<Integer, Map<String, Object>> computeValue(@NonNull Class<?> type) {
        return new TreeMap<>();
    }
}