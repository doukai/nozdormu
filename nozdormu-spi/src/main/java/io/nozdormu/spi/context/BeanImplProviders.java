package io.nozdormu.spi.context;

import java.util.TreeMap;
import java.util.function.Supplier;

public class BeanImplProviders extends ClassValue<TreeMap<Integer, Supplier<?>>> {

    @Override
    protected TreeMap<Integer, Supplier<?>> computeValue(Class<?> type) {
        return new TreeMap<>();
    }

    public Integer put(Class<?> beanClass, Integer priority, Supplier<?> supplier) {
        TreeMap<Integer, Supplier<?>> integerSupplierTreeMap = get(beanClass);
        if (integerSupplierTreeMap.containsKey(priority)) {
            return put(beanClass, priority - 1, supplier);
        } else {
            integerSupplierTreeMap.put(priority, supplier);
            return priority;
        }
    }
}