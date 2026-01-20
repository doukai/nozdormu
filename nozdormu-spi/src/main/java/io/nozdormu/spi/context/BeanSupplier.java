package io.nozdormu.spi.context;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class BeanSupplier {

    private Map<String, Map<String, Object>> qualifiers = new HashMap<>();

    private Integer priority;

    private Supplier<?> supplier;

    public Map<String, Map<String, Object>> getQualifiers() {
        return qualifiers;
    }

    public BeanSupplier setQualifiers(Map<String, Map<String, Object>> qualifiers) {
        this.qualifiers = qualifiers;
        return this;
    }

    public Integer getPriority() {
        return priority;
    }

    public BeanSupplier setPriority(Integer priority) {
        this.priority = priority;
        return this;
    }

    public Supplier<?> getSupplier() {
        return supplier;
    }

    public BeanSupplier setSupplier(Supplier<?> supplier) {
        this.supplier = supplier;
        return this;
    }
}