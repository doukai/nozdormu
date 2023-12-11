package io.nozdormu.spi.context;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ScopeInstances extends ClassValue<Map<String, Object>> {
    @Override
    protected Map<String, Object> computeValue(Class<?> type) {
        return new ConcurrentHashMap<>();
    }
}