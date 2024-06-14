package io.nozdormu.spi.context;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class BeanProviders extends ClassValue<Map<String, Supplier<?>>> {

    @Override
    protected Map<String, Supplier<?>> computeValue(Class<?> type) {
        return new HashMap<>();
    }
}