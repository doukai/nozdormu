package io.nozdormu.spi.context;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ScopeInstances extends ClassValue<Map<String, Object>> {
    @Override
    protected Map<String, Object> computeValue(@NonNull Class<?> type) {
        return new ConcurrentHashMap<>();
    }
}