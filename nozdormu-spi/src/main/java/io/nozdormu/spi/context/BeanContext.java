package io.nozdormu.spi.context;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Provider;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class BeanContext {

    private static final Map<String, Map<String, BeanSupplier>> BEAN_IMPL_SUPPLIER_MAP = new ConcurrentHashMap<>();

    private static ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();

    private BeanContext() {
    }

    public static void setClassLoader(ClassLoader classLoader) {
        contextClassLoader = classLoader;
        Thread.currentThread().setContextClassLoader(classLoader);
    }

    public static <T> T get(Class<T> beanClass) {
        return get(beanClass, Map.of());
    }

    public static <T> T get(Class<T> beanClass, Map<String, Map<String, Object>> qualifiers) {
        return getSupplierOptional(beanClass, qualifiers)
                .map(Supplier::get)
                .orElse(null);
    }

    public static <T> Mono<T> getMono(Class<T> beanClass) {
        return getMono(beanClass, Map.of());
    }

    public static <T> Mono<T> getMono(Class<T> beanClass, Map<String, Map<String, Object>> qualifiers) {
        return getMonoSupplierOptional(beanClass, qualifiers)
                .map(Supplier::get)
                .orElseGet(Mono::empty);
    }

    public static <T> Provider<T> getProvider(Class<T> beanClass) {
        return getProvider(beanClass, Map.of());
    }

    public static <T> Provider<T> getProvider(Class<T> beanClass, Map<String, Map<String, Object>> qualifiers) {
        return getSupplierOptional(beanClass, qualifiers)
                .map(supplier -> (Provider<T>) supplier::get)
                .orElse(null);
    }

    public static <T> Provider<Mono<T>> getMonoProvider(Class<T> beanClass) {
        return getMonoProvider(beanClass, Map.of());
    }

    public static <T> Provider<Mono<T>> getMonoProvider(Class<T> beanClass, Map<String, Map<String, Object>> qualifiers) {
        return getMonoSupplierOptional(beanClass, qualifiers)
                .map(supplier -> (Provider<Mono<T>>) supplier::get)
                .orElse(null);
    }

    public static <T> Instance<T> getInstance(Class<T> beanClass) {
        return getInstance(beanClass, Map.of());
    }

    public static <T> Instance<T> getInstance(Class<T> beanClass, Map<String, Map<String, Object>> qualifiers) {
        return new InstanceImpl<>(getImplSupplierMap(beanClass, qualifiers));
    }

    public static <T> Instance<T> getInstance(Map<String, Map<String, Object>> qualifiers) {
        return new InstanceImpl<>(getImplSupplierMap(qualifiers));
    }

    public static <T> Instance<Mono<T>> getMonoInstance(Class<T> beanClass) {
        return getMonoInstance(beanClass, Map.of());
    }

    public static <T> Instance<Mono<T>> getMonoInstance(Class<T> beanClass, Map<String, Map<String, Object>> qualifiers) {
        return new InstanceImpl<>(getImplSupplierMap(beanClass, qualifiers));
    }

    public static <T> Instance<Mono<T>> getMonoInstance(Map<String, Map<String, Object>> qualifiers) {
        return new InstanceImpl<>(getImplSupplierMap(qualifiers));
    }

    public static <T> Optional<T> getOptional(Class<T> beanClass) {
        return getOptional(beanClass, Map.of());
    }

    public static <T> Optional<T> getOptional(Class<T> beanClass, Map<String, Map<String, Object>> qualifiers) {
        return getSupplierOptional(beanClass, qualifiers).map(Supplier::get);
    }

    public static <T> Optional<Mono<T>> getMonoOptional(Class<T> beanClass) {
        return getMonoOptional(beanClass, Map.of());
    }

    public static <T> Optional<Mono<T>> getMonoOptional(Class<T> beanClass, Map<String, Map<String, Object>> qualifiers) {
        return getMonoSupplierOptional(beanClass, qualifiers).map(Supplier::get);
    }

    public static <T> Optional<Provider<T>> getProviderOptional(Class<T> beanClass) {
        return getProviderOptional(beanClass, Map.of());
    }

    public static <T> Optional<Provider<T>> getProviderOptional(Class<T> beanClass, Map<String, Map<String, Object>> qualifiers) {
        return getSupplierOptional(beanClass, qualifiers)
                .map(supplier -> supplier::get);
    }

    public static <T> Optional<Provider<Mono<T>>> getMonoProviderOptional(Class<T> beanClass) {
        return getMonoProviderOptional(beanClass, Map.of());
    }

    public static <T> Optional<Provider<Mono<T>>> getMonoProviderOptional(Class<T> beanClass, Map<String, Map<String, Object>> qualifiers) {
        return getMonoSupplierOptional(beanClass, qualifiers)
                .map(supplier -> supplier::get);
    }

    @SuppressWarnings("unchecked")
    private static <T> Stream<Supplier<T>> getSupplierStream(Class<T> beanClass, Map<String, Map<String, Object>> qualifiers) {
        return Stream.ofNullable(
                        BEAN_IMPL_SUPPLIER_MAP.computeIfAbsent(
                                beanClass.getName(),
                                k ->
                                        StreamSupport.stream(ServiceLoader.load(BeanSuppliers.class, contextClassLoader).spliterator(), false)
                                                .filter(provider -> provider.getBeanSuppliers().containsKey(beanClass.getName()))
                                                .flatMap(provider -> provider.getBeanSuppliers().get(beanClass.getName()).entrySet().stream())
                                                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (x, y) -> y))
                        )
                )
                .flatMap(implMap -> implMap.entrySet().stream())
                .filter(implEntry ->
                        qualifiers.entrySet().stream()
                                .allMatch(qualifierEntry ->
                                        implEntry.getValue().getQualifiers().containsKey(qualifierEntry.getKey()) &&
                                                qualifierEntry.getValue().entrySet().stream()
                                                        .allMatch(attributesEntry ->
                                                                implEntry.getValue().getQualifiers().get(qualifierEntry.getKey()).containsKey(attributesEntry.getKey()) &&
                                                                        attributesEntry.getValue().equals(implEntry.getValue().getQualifiers().get(qualifierEntry.getKey()).get(attributesEntry.getKey()))
                                                        )
                                )
                )
                .sorted(Comparator.comparing(implEntry -> implEntry.getValue().getPriority(), Comparator.nullsLast(Integer::compareTo)))
                .map(implEntry -> (Supplier<T>) implEntry.getValue().getSupplier());
    }

    @SuppressWarnings("unchecked")
    private static <T> Stream<Supplier<Mono<T>>> getMonoSupplierStream(Class<T> beanClass, Map<String, Map<String, Object>> qualifiers) {
        return getSupplierStream(beanClass, qualifiers)
                .map(supplier -> (Supplier<Mono<T>>) supplier);
    }

    private static <T> Optional<Supplier<T>> getSupplierOptional(Class<T> beanClass, Map<String, Map<String, Object>> qualifiers) {
        return getSupplierStream(beanClass, qualifiers).findFirst();
    }

    private static <T> Optional<Supplier<Mono<T>>> getMonoSupplierOptional(Class<T> beanClass, Map<String, Map<String, Object>> qualifiers) {
        return getMonoSupplierStream(beanClass, qualifiers).findFirst();
    }

    public static <T> List<T> getList(Class<T> beanClass) {
        return getList(beanClass, Map.of());
    }

    public static <T> List<T> getList(Class<T> beanClass, Map<String, Map<String, Object>> qualifiers) {
        return getSupplierStream(beanClass, qualifiers)
                .map(Supplier::get)
                .collect(Collectors.toList());
    }

    public static <T> List<Mono<T>> getMonoList(Class<T> beanClass) {
        return getMonoList(beanClass, Map.of());
    }

    public static <T> List<Mono<T>> getMonoList(Class<T> beanClass, Map<String, Map<String, Object>> qualifiers) {
        return getMonoSupplierStream(beanClass, qualifiers)
                .map(Supplier::get)
                .collect(Collectors.toList());
    }

    public static <T> List<Provider<T>> getProviderList(Class<T> beanClass) {
        return getProviderList(beanClass, Map.of());
    }

    public static <T> List<Provider<T>> getProviderList(Class<T> beanClass, Map<String, Map<String, Object>> qualifiers) {
        return getSupplierStream(beanClass, qualifiers)
                .map(supplier -> (Provider<T>) ((Supplier<T>) supplier)::get)
                .collect(Collectors.toList());
    }

    public static <T> List<Provider<Mono<T>>> getMonoProviderList(Class<T> beanClass) {
        return getMonoProviderList(beanClass, Map.of());
    }

    public static <T> List<Provider<Mono<T>>> getMonoProviderList(Class<T> beanClass, Map<String, Map<String, Object>> qualifiers) {
        return getMonoSupplierStream(beanClass, qualifiers)
                .map(supplier -> (Provider<Mono<T>>) ((Supplier<Mono<T>>) supplier)::get)
                .collect(Collectors.toList());
    }

    public static <T> Map<String, BeanSupplier> getImplSupplierMap(Class<T> beanClass) {
        return getImplSupplierMap(beanClass, Map.of());
    }

    public static <T> Map<String, BeanSupplier> getImplSupplierMap(Class<T> beanClass, Map<String, Map<String, Object>> qualifiers) {
        return Stream.ofNullable(
                        BEAN_IMPL_SUPPLIER_MAP.computeIfAbsent(
                                beanClass.getName(),
                                k ->
                                        ServiceLoader.load(BeanSuppliers.class, contextClassLoader).stream()
                                                .filter(provider -> provider.get().getBeanSuppliers().containsKey(beanClass.getName()))
                                                .flatMap(provider -> provider.get().getBeanSuppliers().get(beanClass.getName()).entrySet().stream())
                                                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (x, y) -> y))
                        )
                )
                .flatMap(implMap -> implMap.entrySet().stream())
                .filter(implEntry ->
                        qualifiers.entrySet().stream()
                                .allMatch(qualifierEntry ->
                                        implEntry.getValue().getQualifiers().containsKey(qualifierEntry.getKey()) &&
                                                qualifierEntry.getValue().entrySet().stream()
                                                        .allMatch(attributesEntry ->
                                                                implEntry.getValue().getQualifiers().get(qualifierEntry.getKey()).containsKey(attributesEntry.getKey()) &&
                                                                        attributesEntry.getValue().equals(implEntry.getValue().getQualifiers().get(qualifierEntry.getKey()).get(attributesEntry.getKey()))
                                                        )
                                )
                )
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (x, y) -> y));
    }

    public static Map<String, BeanSupplier> getImplSupplierMap(Map<String, Map<String, Object>> qualifiers) {
        return ServiceLoader.load(BeanSuppliers.class, contextClassLoader).stream()
                .flatMap(provider -> provider.get().getBeanSuppliers().values().stream())
                .flatMap(entry -> entry.entrySet().stream())
                .filter(entry ->
                        qualifiers.entrySet().stream()
                                .allMatch(qualifierEntry ->
                                        entry.getValue().getQualifiers().containsKey(qualifierEntry.getKey()) &&
                                                qualifierEntry.getValue().entrySet().stream()
                                                        .allMatch(attributesEntry ->
                                                                entry.getValue().getQualifiers().get(qualifierEntry.getKey()).containsKey(attributesEntry.getKey()) &&
                                                                        attributesEntry.getValue().equals(entry.getValue().getQualifiers().get(qualifierEntry.getKey()).get(attributesEntry.getKey()))
                                                        )
                                )
                )
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (x, y) -> y));
    }

    public static Map<String, Map<String, BeanSupplier>> getBeanImplSupplierMap() {
        return BEAN_IMPL_SUPPLIER_MAP;
    }
}
