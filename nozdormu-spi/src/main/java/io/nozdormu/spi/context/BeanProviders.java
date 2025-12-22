package io.nozdormu.spi.context;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class BeanProviders extends ClassValue<Map<String, Supplier<?>>> {

    @Override
    protected Map<String, Supplier<?>> computeValue(@NonNull Class<?> type) {
        return new HashMap<>();
    }
}