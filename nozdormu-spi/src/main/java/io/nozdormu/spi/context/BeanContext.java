package io.nozdormu.spi.context;

import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class BeanContext {

    private static final BeanProviders CONTEXT = new BeanProviders();

    private static final BeanImplProviders IMPL_CONTEXT = new BeanImplProviders();

    private static final BeanImplMeta IMPL_META = new BeanImplMeta();

    static {
        load(BeanContext.class.getClassLoader());
    }

    private BeanContext() {
    }

    public static void load(ClassLoader classLoader) {
        Thread.currentThread().setContextClassLoader(classLoader);
        ServiceLoader.load(BeanContextLoader.class, classLoader).forEach(BeanContextLoader::load);
    }

    public static void put(Class<?> beanClass, Supplier<?> supplier) {
        put(beanClass, null, null, false, supplier, Map.of());
    }

    public static void put(Class<?> beanClass, Supplier<?> supplier, Map<String, Object> meta) {
        put(beanClass, null, null, false, supplier, meta);
    }

    public static void put(Class<?> beanClass, String name, Supplier<?> supplier) {
        put(beanClass, name, null, false, supplier, Map.of(Named.class.getName(), name));
    }

    public static void put(Class<?> beanClass, String name, Supplier<?> supplier, Map<String, Object> meta) {
        put(beanClass, name, null, false, supplier, meta);
    }

    public static void put(Class<?> beanClass, boolean isDefault, Supplier<?> supplier) {
        put(beanClass, null, null, isDefault, supplier, Map.of(Default.class.getName(), isDefault));
    }

    public static void put(Class<?> beanClass, boolean isDefault, Supplier<?> supplier, Map<String, Object> meta) {
        put(beanClass, null, null, isDefault, supplier, meta);
    }

    public static void put(Class<?> beanClass, Integer priority, Supplier<?> supplier) {
        put(beanClass, priority, false, supplier);
    }

    public static void put(Class<?> beanClass, String name, Integer priority, Supplier<?> supplier) {
        put(beanClass, name, priority, false, supplier, Map.of(Named.class.getName(), name));
    }

    public static void put(Class<?> beanClass, String name, Integer priority, Supplier<?> supplier, Map<String, Object> meta) {
        put(beanClass, name, priority, false, supplier, meta);
    }

    public static void put(Class<?> beanClass, String name, boolean isDefault, Supplier<?> supplier) {
        put(beanClass, name, null, isDefault, supplier, Map.of(Named.class.getName(), name, Default.class.getName(), isDefault));
    }

    public static void put(Class<?> beanClass, String name, boolean isDefault, Supplier<?> supplier, Map<String, Object> meta) {
        put(beanClass, name, null, isDefault, supplier, meta);
    }

    public static void put(Class<?> beanClass, Integer priority, boolean isDefault, Supplier<?> supplier) {
        put(beanClass, null, priority, isDefault, supplier, Map.of(Default.class.getName(), isDefault));
    }

    public static void put(Class<?> beanClass, Integer priority, boolean isDefault, Supplier<?> supplier, Map<String, Object> meta) {
        put(beanClass, null, priority, isDefault, supplier, meta);
    }

    public static void put(Class<?> beanClass, String name, Integer priority, boolean isDefault, Supplier<?> supplier, Map<String, Object> meta) {
        CONTEXT.get(beanClass).put(beanClass.getName(), supplier);
        if (name != null) {
            CONTEXT.get(beanClass).put(name, supplier);
        }
        Integer indexedPriority = IMPL_CONTEXT.put(beanClass, Objects.requireNonNullElse(priority, Integer.MAX_VALUE), supplier);
        if (meta != null) {
            IMPL_META.get(beanClass).put(indexedPriority, meta);
        }
        if (isDefault) {
            CONTEXT.get(beanClass).put(Default.class.getName(), supplier);
        }
    }

    public static <T> T get(Class<T> beanClass) {
        return get(beanClass, beanClass.getName());
    }

    public static <T> T get(Class<T> beanClass, String name) {
        return Optional.ofNullable(getSupplier(beanClass, name))
                .map(Supplier::get)
                .orElse(null);
    }

    public static <T> Mono<T> getMono(Class<T> beanClass) {
        return getMono(beanClass, beanClass.getName());
    }

    public static <T> Mono<T> getMono(Class<T> beanClass, String name) {
        return getMonoSupplier(beanClass, name).get();
    }

    public static <T> Provider<T> getProvider(Class<T> beanClass) {
        return getProvider(beanClass, beanClass.getName());
    }

    public static <T> Provider<T> getProvider(Class<T> beanClass, String name) {
        return Optional.ofNullable(getSupplier(beanClass, name))
                .map(supplier -> (Provider<T>) supplier::get)
                .orElse(null);
    }

    public static <T> Provider<Mono<T>> getMonoProvider(Class<T> beanClass) {
        return getMonoProvider(beanClass, beanClass.getName());
    }

    public static <T> Provider<Mono<T>> getMonoProvider(Class<T> beanClass, String name) {
        return getMonoSupplier(beanClass, name)::get;
    }

    public static <T> Instance<T> getInstance(Class<T> beanClass, String... names) {
        return new InstanceImpl<>(getProviderList(beanClass, names));
    }

    public static <T> Instance<T> getInstance(Class<T> beanClass, String name) {
        return new InstanceImpl<>(Collections.singletonList(getProvider(beanClass, name)));
    }

    public static <T> Instance<Mono<T>> getMonoInstance(Class<T> beanClass, String... names) {
        return new InstanceImpl<>(getMonoProviderList(beanClass, names));
    }

    public static <T> Instance<Mono<T>> getMonoInstance(Class<T> beanClass, String name) {
        return new InstanceImpl<>(Collections.singletonList(getMonoProvider(beanClass, name)));
    }

    public static <T> Optional<T> getOptional(Class<T> beanClass) {
        return getOptional(beanClass, beanClass.getName());
    }

    public static <T> Optional<T> getOptional(Class<T> beanClass, String name) {
        return getSupplierOptional(beanClass, name).map(Supplier::get);
    }

    public static <T> Optional<Mono<T>> getMonoOptional(Class<T> beanClass) {
        return getMonoOptional(beanClass, beanClass.getName());
    }

    public static <T> Optional<Mono<T>> getMonoOptional(Class<T> beanClass, String name) {
        return getMonoSupplierOptional(beanClass, name).map(Supplier::get);
    }

    public static <T> Optional<Provider<T>> getProviderOptional(Class<T> beanClass) {
        return getProviderOptional(beanClass, beanClass.getName());
    }

    public static <T> Optional<Provider<T>> getProviderOptional(Class<T> beanClass, String name) {
        return getSupplierOptional(beanClass, name).map(supplier -> supplier::get);
    }

    public static <T> Optional<Provider<Mono<T>>> getMonoProviderOptional(Class<T> beanClass) {
        return getMonoProviderOptional(beanClass, beanClass.getName());
    }

    public static <T> Optional<Provider<Mono<T>>> getMonoProviderOptional(Class<T> beanClass, String name) {
        return getMonoSupplierOptional(beanClass, name).map(supplier -> supplier::get);
    }

    private static <T> Supplier<T> getSupplier(Class<T> beanClass, String name) {
        return getSupplierOptional(beanClass, name).orElse(null);
    }

    private static <T> Supplier<Mono<T>> getMonoSupplier(Class<T> beanClass, String name) {
        return getMonoSupplierOptional(beanClass, name).orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static <T> Optional<Supplier<T>> getSupplierOptional(Class<T> beanClass, String name) {
        Supplier<?> supplier = CONTEXT.get(beanClass).get(name);
        return Optional.ofNullable((Supplier<T>) supplier);
    }

    @SuppressWarnings("unchecked")
    private static <T> Optional<Supplier<Mono<T>>> getMonoSupplierOptional(Class<T> beanClass, String name) {
        Supplier<?> supplier = CONTEXT.get(beanClass).get(name);
        return Optional.ofNullable((Supplier<Mono<T>>) supplier);
    }

    @SuppressWarnings("unchecked")
    public static <T> List<T> getList(Class<T> beanClass, String... names) {
        if (names != null && names.length > 0) {
            return CONTEXT.get(beanClass).entrySet().stream()
                    .filter(entry -> Arrays.stream(names).allMatch(name -> entry.getKey().equals(name)))
                    .map(entry -> (T) entry.getValue().get())
                    .collect(Collectors.toList());
        }
        return IMPL_CONTEXT.get(beanClass).values().stream()
                .map(supplier -> (T) supplier.get())
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    public static <T> List<Mono<T>> getMonoList(Class<T> beanClass, String... names) {
        if (names != null && names.length > 0) {
            return CONTEXT.get(beanClass).entrySet().stream()
                    .filter(entry -> Arrays.stream(names).allMatch(name -> entry.getKey().equals(name)))
                    .map(entry -> (Mono<T>) entry.getValue().get())
                    .collect(Collectors.toList());
        }
        return IMPL_CONTEXT.get(beanClass).values().stream()
                .map(supplier -> (Mono<T>) supplier.get())
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    public static <T> List<Provider<T>> getProviderList(Class<T> beanClass, String... names) {
        if (names != null && names.length > 0) {
            return CONTEXT.get(beanClass).entrySet().stream()
                    .filter(entry -> Arrays.stream(names).allMatch(name -> entry.getKey().equals(name)))
                    .map(entry -> (Provider<T>) ((Supplier<T>) entry.getValue())::get)
                    .collect(Collectors.toList());
        }
        return IMPL_CONTEXT.get(beanClass).values().stream()
                .map(supplier -> (Provider<T>) ((Supplier<T>) supplier)::get)
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    public static <T> List<Provider<Mono<T>>> getMonoProviderList(Class<T> beanClass, String... names) {
        if (names != null && names.length > 0) {
            return CONTEXT.get(beanClass).entrySet().stream()
                    .filter(entry -> Arrays.stream(names).allMatch(name -> entry.getKey().equals(name)))
                    .map(entry -> (Provider<Mono<T>>) ((Supplier<Mono<T>>) entry.getValue())::get)
                    .collect(Collectors.toList());
        }
        return IMPL_CONTEXT.get(beanClass).values().stream()
                .map(supplier -> (Provider<Mono<T>>) ((Supplier<Mono<T>>) supplier)::get)
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    public static <T> List<Tuple2<Map<String, Object>, T>> getListWithMeta(Class<T> beanClass) {
        return IMPL_CONTEXT.get(beanClass).entrySet().stream()
                .map(entry -> Tuples.of(IMPL_META.get(beanClass).get(entry.getKey()), (T) entry.getValue().get()))
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    public static <T> List<Tuple2<Map<String, Object>, Provider<T>>> getProviderListWithMeta(Class<T> beanClass) {
        return IMPL_CONTEXT.get(beanClass).entrySet().stream()
                .map(entry -> Tuples.of(IMPL_META.get(beanClass).get(entry.getKey()), (Provider<T>) ((Supplier<T>) entry.getValue())::get))
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private static <T> List<Tuple2<Map<String, Object>, Supplier<T>>> getSupplierListWithMeta(Class<T> beanClass) {
        return IMPL_CONTEXT.get(beanClass).entrySet().stream()
                .map(entry -> Tuples.of(IMPL_META.get(beanClass).get(entry.getKey()), (Supplier<T>) entry.getValue()))
                .collect(Collectors.toList());
    }
}
