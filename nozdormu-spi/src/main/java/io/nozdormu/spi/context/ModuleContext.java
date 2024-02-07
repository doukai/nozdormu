package io.nozdormu.spi.context;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

public interface ModuleContext {

    String CLASS_PREFIX = "class:";

    String IMPL_PREFIX = "impl:";

    String NAME_PREFIX = "name:";

    String DEFAULT_KEY = "default";

    String PRIORITY_PREFIX = "priority:";

    <T> Supplier<T> get(Class<T> beanClass);

    <T> Supplier<T> get(Class<T> beanClass, String key);

    <T> Optional<Supplier<T>> getOptional(Class<T> beanClass);

    <T> Optional<Supplier<T>> getOptional(Class<T> beanClass, String key);

    <T> Map<String, Supplier<?>> getMap(Class<T> beanClass);

    <T> Optional<Map<String, Supplier<?>>> getMapOptional(Class<T> beanClass);
}
