package io.nozdormu.spi.context;

import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Provider;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class BeanContext {

    private static final BeanProviders CONTEXT = new BeanProviders();

    private static final BeanImplProviders IMPL_CONTEXT = new BeanImplProviders();

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
        put(beanClass, beanClass.getName(), Integer.MAX_VALUE, false, supplier);
    }

    public static void put(Class<?> beanClass, String name, Supplier<?> supplier) {
        put(beanClass, name, Integer.MAX_VALUE, false, supplier);
    }

    public static void put(Class<?> beanClass, boolean isDefault, Supplier<?> supplier) {
        put(beanClass, beanClass.getName(), Integer.MAX_VALUE, isDefault, supplier);
    }

    public static void put(Class<?> beanClass, Integer priority, Supplier<?> supplier) {
        put(beanClass, beanClass.getName(), priority, false, supplier);
    }

    public static void put(Class<?> beanClass, String name, Integer priority, Supplier<?> supplier) {
        put(beanClass, name, priority, false, supplier);
    }

    public static void put(Class<?> beanClass, String name, boolean isDefault, Supplier<?> supplier) {
        put(beanClass, name, Integer.MAX_VALUE, isDefault, supplier);
    }

    public static void put(Class<?> beanClass, Integer priority, boolean isDefault, Supplier<?> supplier) {
        put(beanClass, beanClass.getName(), priority, isDefault, supplier);
    }

    public static void put(Class<?> beanClass, String name, Integer priority, boolean isDefault, Supplier<?> supplier) {
        CONTEXT.get(beanClass).put(name, supplier);
        IMPL_CONTEXT.get(beanClass).put(priority, supplier);
        if (isDefault) {
            CONTEXT.get(beanClass).put(Default.class.getCanonicalName(), supplier);
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
        return getSupplierOptional(beanClass, name)
                .orElseGet(() -> getAndCacheSupplier(beanClass, name).orElse(null));
    }

    private static <T> Supplier<Mono<T>> getMonoSupplier(Class<T> beanClass, String name) {
        return getMonoSupplierOptional(beanClass, name)
                .orElseGet(() -> getAndCacheMonoSupplier(beanClass, name).orElse(null));
    }

    private static <T> Optional<Supplier<T>> getSupplierOptionalOrCache(Class<T> beanClass, String name) {
        return getSupplierOptional(beanClass, name)
                .or(() -> getAndCacheSupplier(beanClass, name));
    }

    private static <T> Optional<Supplier<Mono<T>>> getMonoSupplierOptionalOrCache(Class<T> beanClass, String name) {
        return getMonoSupplierOptional(beanClass, name)
                .or(() -> getAndCacheMonoSupplier(beanClass, name));
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
    private static <T> Optional<Supplier<T>> getAndCacheSupplier(Class<T> beanClass, String name) {
        return Optional.ofNullable((Supplier<T>) CONTEXT.get(beanClass).get(name));
    }

    @SuppressWarnings("unchecked")
    private static <T> Optional<Supplier<Mono<T>>> getAndCacheMonoSupplier(Class<T> beanClass, String name) {
        return Optional.ofNullable((Supplier<Mono<T>>) CONTEXT.get(beanClass).get(name));
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
}
