package io.nozdormu.spi.context;

import jakarta.inject.Provider;
import org.tinylog.Logger;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BeanContext {

    public static final String CLASS_PREFIX = "class:";

    public static final String IMPL_PREFIX = "impl:";

    public static final String NAME_PREFIX = "name:";

    public static final String DEFAULT_KEY = "default";

    public static final String PRIORITY_PREFIX = "priority:";

    private static final BeanProviders CONTEXT = new BeanProviders();

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
        put(beanClass, CLASS_PREFIX + beanClass.getName(), supplier);
    }

    public static void putDefault(Class<?> beanClass, Supplier<?> supplier) {
        put(beanClass, DEFAULT_KEY, supplier);
    }

    public static void putName(Class<?> beanClass, Supplier<?> supplier, String name) {
        put(beanClass, NAME_PREFIX + name, supplier);
    }

    public static void putPriority(Class<?> beanClass, Integer priority, Supplier<?> supplier) {
        put(beanClass, PRIORITY_PREFIX + Optional.ofNullable(priority).orElse(Integer.MAX_VALUE), supplier);
    }

    public static void put(Class<?> beanClass, String key, Supplier<?> supplier) {
        CONTEXT.get(beanClass).put(key, supplier);
    }

    public static <T> T get(Class<T> beanClass) {
        return get(beanClass, CLASS_PREFIX + beanClass.getName());
    }

    public static <T> T getDefault(Class<T> beanClass) {
        return get(beanClass, DEFAULT_KEY);
    }

    public static <T> T getName(Class<T> beanClass, String name) {
        return get(beanClass, NAME_PREFIX + name);
    }

    public static <T> T get(Class<T> beanClass, String key) {
        return getSupplier(beanClass, key).get();
    }

    public static <T> Mono<T> getMono(Class<T> beanClass) {
        return getMono(beanClass, CLASS_PREFIX + beanClass.getName());
    }

    public static <T> Mono<T> getDefaultMono(Class<T> beanClass) {
        return getMono(beanClass, DEFAULT_KEY);
    }

    public static <T> Mono<T> getNameMono(Class<T> beanClass, String name) {
        return getMono(beanClass, NAME_PREFIX + name);
    }

    public static <T> Mono<T> getMono(Class<T> beanClass, String key) {
        return getMonoSupplier(beanClass, key).get();
    }

    public static <T> Provider<T> getProvider(Class<T> beanClass) {
        return getProvider(beanClass, CLASS_PREFIX + beanClass.getName());
    }

    public static <T> Provider<T> getDefaultProvider(Class<T> beanClass) {
        return getProvider(beanClass, DEFAULT_KEY);
    }

    public static <T> Provider<T> getNameProvider(Class<T> beanClass, String name) {
        return getProvider(beanClass, NAME_PREFIX + name);
    }

    public static <T> Provider<T> getProvider(Class<T> beanClass, String name) {
        return getSupplier(beanClass, name)::get;
    }

    public static <T> Provider<Mono<T>> getMonoProvider(Class<T> beanClass) {
        return getMonoProvider(beanClass, CLASS_PREFIX + beanClass.getName());
    }

    public static <T> Provider<Mono<T>> getDefaultMonoProvider(Class<T> beanClass) {
        return getMonoProvider(beanClass, DEFAULT_KEY);
    }

    public static <T> Provider<Mono<T>> getNameMonoProvider(Class<T> beanClass, String name) {
        return getMonoProvider(beanClass, NAME_PREFIX + name);
    }

    public static <T> Provider<Mono<T>> getMonoProvider(Class<T> beanClass, String key) {
        return getMonoSupplier(beanClass, key)::get;
    }

    public static <T> Optional<T> getOptional(Class<T> beanClass) {
        return getOptional(beanClass, CLASS_PREFIX + beanClass.getName());
    }

    public static <T> Optional<T> getDefaultOptional(Class<T> beanClass) {
        return getOptional(beanClass, DEFAULT_KEY);
    }

    public static <T> Optional<T> getNameOptional(Class<T> beanClass, String name) {
        return getOptional(beanClass, NAME_PREFIX + name);
    }

    public static <T> Optional<T> getOptional(Class<T> beanClass, String key) {
        return getSupplierOptional(beanClass, key).map(Supplier::get);
    }

    public static <T> Optional<Mono<T>> getMonoOptional(Class<T> beanClass) {
        return getMonoOptional(beanClass, CLASS_PREFIX + beanClass.getName());
    }

    public static <T> Optional<Mono<T>> getDefaultMonoOptional(Class<T> beanClass) {
        return getMonoOptional(beanClass, DEFAULT_KEY);
    }

    public static <T> Optional<Mono<T>> getNameMonoOptional(Class<T> beanClass, String name) {
        return getMonoOptional(beanClass, NAME_PREFIX + name);
    }

    public static <T> Optional<Mono<T>> getMonoOptional(Class<T> beanClass, String key) {
        return getMonoSupplierOptional(beanClass, key).map(Supplier::get);
    }

    public static <T> Optional<Provider<T>> getProviderOptional(Class<T> beanClass) {
        return getProviderOptional(beanClass, CLASS_PREFIX + beanClass.getName());
    }

    public static <T> Optional<Provider<T>> getDefaultProviderOptional(Class<T> beanClass) {
        return getProviderOptional(beanClass, DEFAULT_KEY);
    }

    public static <T> Optional<Provider<T>> getNameProviderOptional(Class<T> beanClass, String name) {
        return getProviderOptional(beanClass, NAME_PREFIX + name);
    }

    public static <T> Optional<Provider<T>> getProviderOptional(Class<T> beanClass, String key) {
        return getSupplierOptional(beanClass, key).map(supplier -> supplier::get);
    }

    public static <T> Optional<Provider<Mono<T>>> getMonoProviderOptional(Class<T> beanClass) {
        return getMonoProviderOptional(beanClass, CLASS_PREFIX + beanClass.getName());
    }

    public static <T> Optional<Provider<Mono<T>>> getDefaultMonoProviderOptional(Class<T> beanClass) {
        return getMonoProviderOptional(beanClass, DEFAULT_KEY);
    }

    public static <T> Optional<Provider<Mono<T>>> getNameMonoProviderOptional(Class<T> beanClass, String name) {
        return getMonoProviderOptional(beanClass, NAME_PREFIX + name);
    }

    public static <T> Optional<Provider<Mono<T>>> getMonoProviderOptional(Class<T> beanClass, String key) {
        return getMonoSupplierOptional(beanClass, key).map(supplier -> supplier::get);
    }

    private static <T> Supplier<T> getSupplier(Class<T> beanClass, String key) {
        return getSupplierOptional(beanClass, key)
                .orElseGet(() -> getAndCacheSupplier(beanClass, key).orElse(null));
    }

    private static <T> Supplier<Mono<T>> getMonoSupplier(Class<T> beanClass, String key) {
        return getMonoSupplierOptional(beanClass, key)
                .orElseGet(() -> getAndCacheMonoSupplier(beanClass, key).orElse(null));
    }

    @SuppressWarnings("unchecked")
    private static <T> Optional<Supplier<T>> getSupplierOptional(Class<T> beanClass, String key) {
        Supplier<?> supplier = CONTEXT.get(beanClass).get(key);
        return Optional.ofNullable((Supplier<T>) supplier);
    }

    @SuppressWarnings("unchecked")
    private static <T> Optional<Supplier<Mono<T>>> getMonoSupplierOptional(Class<T> beanClass, String key) {
        Supplier<?> supplier = CONTEXT.get(beanClass).get(key);
        return Optional.ofNullable((Supplier<Mono<T>>) supplier);
    }

    @SuppressWarnings("unchecked")
    private static <T> Optional<Supplier<T>> getAndCacheSupplier(Class<T> beanClass, String key) {
        Logger.debug("search bean instance for class {} key {}", beanClass.getName(), key);
        return Optional.ofNullable((Supplier<T>) CONTEXT.get(beanClass).get(key));
    }

    @SuppressWarnings("unchecked")
    private static <T> Optional<Supplier<Mono<T>>> getAndCacheMonoSupplier(Class<T> beanClass, String key) {
        Logger.debug("search bean instance for class {} key {}", beanClass.getName(), key);
        return Optional.ofNullable((Supplier<Mono<T>>) CONTEXT.get(beanClass).get(key));
    }

    public static <T> Stream<Map.Entry<String, Supplier<?>>> getImplEntryStream(Class<T> beanClass) {
        Logger.debug("search bean map for class {}", beanClass.getName());
        return Stream.ofNullable(CONTEXT.get(beanClass))
                .flatMap(map -> map.entrySet().stream())
                .filter(entry -> entry.getKey().startsWith(IMPL_PREFIX))
                .collect(
                        Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue,
                                (v1, v2) -> v1
                        )
                )
                .entrySet()
                .stream()
                .map(entry -> new AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue()));
    }

    public static <T> Stream<Map.Entry<String, Supplier<?>>> getPriorityEntryStream(Class<T> beanClass) {
        Logger.debug("search bean map for class {}", beanClass.getName());
        return Stream.ofNullable(CONTEXT.get(beanClass))
                .flatMap(map -> map.entrySet().stream())
                .filter(entry -> entry.getKey().startsWith(PRIORITY_PREFIX))
                .collect(
                        Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue,
                                (v1, v2) -> v1
                        )
                )
                .entrySet()
                .stream()
                .sorted(
                        Comparator
                                .comparingInt((Map.Entry<String, ? extends Supplier<?>> entry) ->
                                        Integer.parseInt(entry.getKey().replaceFirst(PRIORITY_PREFIX, ""))
                                )
                )
                .map(entry -> new AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue()));
    }

    public static <T> Stream<Map.Entry<String, Supplier<?>>> getNameEntryStream(Class<T> beanClass) {
        Logger.debug("search bean map for class {}", beanClass.getName());
        return Stream.ofNullable(CONTEXT.get(beanClass))
                .flatMap(map -> map.entrySet().stream())
                .filter(entry -> entry.getKey().startsWith(NAME_PREFIX))
                .collect(
                        Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue,
                                (v1, v2) -> v1
                        )
                )
                .entrySet()
                .stream()
                .map(entry -> new AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue()));
    }

    @SuppressWarnings("unchecked")
    public static <T> Map<String, T> getMap(Class<T> beanClass) {
        Logger.debug("search bean map for class {}", beanClass.getName());
        return getNameEntryStream(beanClass)
                .map(entry -> new AbstractMap.SimpleEntry<>(entry.getKey().replaceFirst(NAME_PREFIX, ""), (T) entry.getValue().get()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @SuppressWarnings("unchecked")
    public static <T> Map<String, Mono<T>> getMonoMap(Class<T> beanClass) {
        Logger.debug("search bean map for class {}", beanClass.getName());
        return getNameEntryStream(beanClass)
                .map(entry -> new AbstractMap.SimpleEntry<>(entry.getKey().replaceFirst(NAME_PREFIX, ""), (Mono<T>) entry.getValue().get()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @SuppressWarnings("unchecked")
    public static <T> Map<String, Provider<T>> getProviderMap(Class<T> beanClass) {
        Logger.debug("search bean map for class {}", beanClass.getName());
        return getNameEntryStream(beanClass)
                .map(entry -> {
                            Provider<T> provider = ((Supplier<T>) entry.getValue())::get;
                            return new AbstractMap.SimpleEntry<>(entry.getKey().replaceFirst(NAME_PREFIX, ""), provider);
                        }
                )
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @SuppressWarnings("unchecked")
    public static <T> Map<String, Provider<Mono<T>>> getMonoProviderMap(Class<T> beanClass) {
        Logger.debug("search bean map for class {}", beanClass.getName());
        return getNameEntryStream(beanClass)
                .map(entry -> {
                            Provider<Mono<T>> provider = ((Supplier<Mono<T>>) entry.getValue())::get;
                            return new AbstractMap.SimpleEntry<>(entry.getKey().replaceFirst(NAME_PREFIX, ""), provider);
                        }
                )
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @SuppressWarnings("unchecked")
    public static <T> Map<Class<? extends T>, T> getClassMap(Class<T> beanClass) {
        Logger.debug("search bean map for class {}", beanClass.getName());
        return getImplEntryStream(beanClass)
                .map(entry -> {
                            try {
                                Class<? extends T> implClass = (Class<? extends T>) Class.forName(entry.getKey().replaceFirst(IMPL_PREFIX, ""), false, Thread.currentThread().getContextClassLoader());
                                return new AbstractMap.SimpleEntry<>(implClass, (T) entry.getValue().get());
                            } catch (ClassNotFoundException e) {
                                throw new RuntimeException(e);
                            }
                        }
                )
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @SuppressWarnings("unchecked")
    public static <T> Map<Class<? extends T>, Mono<T>> getClassMonoMap(Class<T> beanClass) {
        Logger.debug("search bean map for class {}", beanClass.getName());
        return getImplEntryStream(beanClass)
                .map(entry -> {
                            try {
                                Class<? extends T> implClass = (Class<? extends T>) Class.forName(entry.getKey().replaceFirst(IMPL_PREFIX, ""), false, Thread.currentThread().getContextClassLoader());
                                return new AbstractMap.SimpleEntry<>(implClass, (Mono<T>) entry.getValue().get());
                            } catch (ClassNotFoundException e) {
                                throw new RuntimeException(e);
                            }
                        }
                )
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @SuppressWarnings("unchecked")
    public static <T> Map<Class<? extends T>, Provider<T>> getClassProviderMap(Class<T> beanClass) {
        Logger.debug("search bean map for class {}", beanClass.getName());
        return getImplEntryStream(beanClass)
                .map(entry -> {
                            try {
                                Class<? extends T> implClass = (Class<? extends T>) Class.forName(entry.getKey().replaceFirst(IMPL_PREFIX, ""), false, Thread.currentThread().getContextClassLoader());
                                Provider<T> provider = ((Supplier<T>) entry.getValue())::get;
                                return new AbstractMap.SimpleEntry<>(implClass, provider);
                            } catch (ClassNotFoundException e) {
                                throw new RuntimeException(e);
                            }
                        }
                )
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @SuppressWarnings("unchecked")
    public static <T> Map<Class<? extends T>, Provider<Mono<T>>> getClassMonoProviderMap(Class<T> beanClass) {
        Logger.debug("search bean map for class {}", beanClass.getName());
        return getImplEntryStream(beanClass)
                .map(entry -> {
                            try {
                                Class<? extends T> implClass = (Class<? extends T>) Class.forName(entry.getKey().replaceFirst(IMPL_PREFIX, ""), false, Thread.currentThread().getContextClassLoader());
                                Provider<Mono<T>> provider = ((Supplier<Mono<T>>) entry.getValue())::get;
                                return new AbstractMap.SimpleEntry<>(implClass, provider);
                            } catch (ClassNotFoundException e) {
                                throw new RuntimeException(e);
                            }
                        }
                )
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @SuppressWarnings("unchecked")
    public static <T> List<T> getList(Class<T> beanClass) {
        Logger.debug("search bean map for class {}", beanClass.getName());
        return getImplEntryStream(beanClass)
                .map(entry -> (T) entry.getValue().get())
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    public static <T> List<Mono<T>> getMonoList(Class<T> beanClass) {
        Logger.debug("search bean map for class {}", beanClass.getName());
        return getImplEntryStream(beanClass)
                .map(entry -> (Mono<T>) entry.getValue().get())
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    public static <T> List<Provider<T>> getProviderList(Class<T> beanClass) {
        Logger.debug("search bean map for class {}", beanClass.getName());
        return getImplEntryStream(beanClass)
                .map(entry -> (Provider<T>) ((Supplier<T>) entry.getValue())::get)
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    public static <T> List<Provider<Mono<T>>> getMonoProviderList(Class<T> beanClass) {
        Logger.debug("search bean map for class {}", beanClass.getName());
        return getImplEntryStream(beanClass)
                .map(entry -> (Provider<Mono<T>>) ((Supplier<Mono<T>>) entry.getValue())::get)
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    public static <T> List<T> getPriorityList(Class<T> beanClass) {
        Logger.debug("search bean map for class {}", beanClass.getName());
        return getPriorityEntryStream(beanClass)
                .map(entry -> (T) entry.getValue().get())
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    public static <T> List<Mono<T>> getPriorityMonoList(Class<T> beanClass) {
        Logger.debug("search bean map for class {}", beanClass.getName());
        return getPriorityEntryStream(beanClass)
                .map(entry -> (Mono<T>) entry.getValue().get())
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    public static <T> List<Provider<T>> getPriorityProviderList(Class<T> beanClass) {
        Logger.debug("search bean map for class {}", beanClass.getName());
        return getPriorityEntryStream(beanClass)
                .map(entry -> (Provider<T>) ((Supplier<T>) entry.getValue())::get)
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    public static <T> List<Provider<Mono<T>>> getPriorityMonoProviderList(Class<T> beanClass) {
        Logger.debug("search bean map for class {}", beanClass.getName());
        return getPriorityEntryStream(beanClass)
                .map(entry -> (Provider<Mono<T>>) ((Supplier<Mono<T>>) entry.getValue())::get)
                .collect(Collectors.toList());
    }

    public static <T> Supplier<T> compute(Class<T> beanClass, Object object) {
        return compute(beanClass, CLASS_PREFIX + beanClass.getName(), object);
    }

    @SuppressWarnings("unchecked")
    public static <T> Supplier<T> compute(Class<T> beanClass, String name, Object object) {
        return (Supplier<T>) CONTEXT.get(beanClass).compute(name, (k, v) -> () -> object);
    }
}
