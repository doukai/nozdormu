package io.nozdormu.spi.context;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import reactor.util.annotation.NonNull;

import java.lang.annotation.Annotation;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static io.nozdormu.spi.utils.QualifierUtil.toQualifierMap;

public class InstanceImpl<T> implements Instance<T> {

    private final Map<String, BeanSupplier> beanSupplierMap;

    public InstanceImpl(Map<String, BeanSupplier> beanSupplierMap) {
        this.beanSupplierMap = beanSupplierMap;
    }

    @Override
    public Instance<T> select(Annotation... qualifiers) {
        if (qualifiers == null || qualifiers.length == 0) {
            return this;
        }
        Map<String, Map<String, Object>> qualifierMap = toQualifierMap(qualifiers);
        return new InstanceImpl<>(
                beanSupplierMap.entrySet().stream()
                        .filter(entry ->
                                qualifierMap.entrySet().stream()
                                        .allMatch(qualifierEntry ->
                                                entry.getValue().getQualifiers().containsKey(qualifierEntry.getKey()) &&
                                                        qualifierEntry.getValue().entrySet().stream()
                                                                .allMatch(attributeEntry ->
                                                                        entry.getValue().getQualifiers().get(qualifierEntry.getKey()).containsKey(attributeEntry.getKey()) &&
                                                                                Objects.equals(
                                                                                        attributeEntry.getValue(),
                                                                                        entry.getValue().getQualifiers().get(qualifierEntry.getKey()).get(attributeEntry.getKey())
                                                                                )
                                                                )
                                        )
                        )
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
        );
    }

    @SuppressWarnings("unchecked")
    @Override
    public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
        if (subtype == null) {
            return (Instance<U>) select(qualifiers);
        }
        Map<String, Map<String, Object>> qualifierMap = toQualifierMap(qualifiers);
        return new InstanceImpl<>(
                beanSupplierMap.entrySet().stream()
                        .filter(entry -> entry.getKey().equals(subtype.getName()))
                        .filter(entry ->
                                qualifierMap.entrySet().stream()
                                        .allMatch(qualifierEntry ->
                                                entry.getValue().getQualifiers().containsKey(qualifierEntry.getKey()) &&
                                                        qualifierEntry.getValue().entrySet().stream()
                                                                .allMatch(attributeEntry ->
                                                                        entry.getValue().getQualifiers().get(qualifierEntry.getKey()).containsKey(attributeEntry.getKey()) &&
                                                                                Objects.equals(
                                                                                        attributeEntry.getValue(),
                                                                                        entry.getValue().getQualifiers().get(qualifierEntry.getKey()).get(attributeEntry.getKey())
                                                                                )
                                                                )
                                        )
                        )
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
        );
    }

    @Override
    public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
        return select(subtype.getRawType(), qualifiers);
    }

    @Override
    public boolean isUnsatisfied() {
        return beanSupplierMap.isEmpty();
    }

    @Override
    public boolean isAmbiguous() {
        return beanSupplierMap.size() > 1;
    }

    @Override
    public void destroy(T instance) {
        // No-op: no lifecycle hooks tracked for instances.
    }

    @SuppressWarnings("unchecked")
    @Override
    public T get() {
        return beanSupplierMap.values().stream()
                .min(Comparator.comparing(BeanSupplier::getPriority, Comparator.nullsLast(Integer::compareTo)))
                .map(beanSupplier -> (T) beanSupplier.getSupplier().get())
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    @Override
    @NonNull
    public Iterator<T> iterator() {
        return beanSupplierMap.values().stream()
                .sorted(Comparator.comparing(BeanSupplier::getPriority, Comparator.nullsLast(Integer::compareTo)))
                .map(beanSupplier -> (T) beanSupplier.getSupplier().get())
                .iterator();
    }

}
