package io.nozdormu.spi.context;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

public abstract class BaseModuleContext implements ModuleContext {

    private static final ClassValue<Map<String, Supplier<?>>> CONTEXT = new BeanProviders();

    protected static void put(Class<?> beanClass, Supplier<?> supplier) {
        put(beanClass, CLASS_PREFIX + beanClass.getName(), supplier);
    }

    protected static void putDefault(Class<?> beanClass, Supplier<?> supplier) {
        put(beanClass, DEFAULT_KEY, supplier);
    }

    protected static void putName(Class<?> beanClass, Supplier<?> supplier, String name) {
        put(beanClass, NAME_PREFIX + name, supplier);
    }

    protected static void putPriority(Class<?> beanClass, Integer priority, Supplier<?> supplier) {
        put(beanClass, PRIORITY_PREFIX + Optional.ofNullable(priority).orElse(Integer.MAX_VALUE), supplier);
    }

    protected static void put(Class<?> beanClass, String key, Supplier<?> supplier) {
        CONTEXT.get(beanClass).put(key, supplier);
    }

    @Override
    public <T> Supplier<T> get(Class<T> beanClass) {
        return get(beanClass, CLASS_PREFIX + beanClass.getName());
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> Supplier<T> get(Class<T> beanClass, String key) {
        return (Supplier<T>) CONTEXT.get(beanClass).get(key);
    }

    @Override
    public <T> Optional<Supplier<T>> getOptional(Class<T> beanClass) {
        return getOptional(beanClass, CLASS_PREFIX + beanClass.getName());
    }

    @Override
    public <T> Optional<Supplier<T>> getOptional(Class<T> beanClass, String key) {
        return Optional.ofNullable(get(beanClass, key));
    }

    @Override
    public <T> Map<String, Supplier<?>> getMap(Class<T> beanClass) {
        return CONTEXT.get(beanClass);
    }

    @Override
    public <T> Optional<Map<String, Supplier<?>>> getMapOptional(Class<T> beanClass) {
        return Optional.ofNullable(getMap(beanClass));
    }
}
