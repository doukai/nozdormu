package io.nozdormu.spi.context;

import java.util.TreeMap;
import java.util.function.Supplier;

public class BeanImplProviders extends ClassValue<TreeMap<Integer, Supplier<?>>> {

    @Override
    protected TreeMap<Integer, Supplier<?>> computeValue(Class<?> type) {
        return new TreeMap<>();
    }
}