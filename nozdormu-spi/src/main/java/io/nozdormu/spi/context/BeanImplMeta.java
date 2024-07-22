package io.nozdormu.spi.context;

import java.util.Map;
import java.util.TreeMap;

public class BeanImplMeta extends ClassValue<TreeMap<Integer, Map<String, Object>>> {

    @Override
    protected TreeMap<Integer, Map<String, Object>> computeValue(Class<?> type) {
        return new TreeMap<>();
    }
}