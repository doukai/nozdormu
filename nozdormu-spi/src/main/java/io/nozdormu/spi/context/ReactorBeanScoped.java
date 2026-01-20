package io.nozdormu.spi.context;

import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public abstract class ReactorBeanScoped {

    private final Map<String, Map<String, Object>> beanContext = new ConcurrentHashMap<>();

    protected abstract Mono<String> getScopedKey();

    @SuppressWarnings("unchecked")
    public <T> Mono<T> get(Class<T> beanClass, Supplier<T> supplier) {
        return getScopedKey()
                .mapNotNull(key -> {
                    if (key == null) {
                        return null;
                    }
                    Map<String, Object> scopedMap = beanContext.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
                    return (T) scopedMap.computeIfAbsent(beanClass.getName(), k -> supplier.get());
                });
    }

    public Mono<Boolean> remove() {
        return getScopedKey()
                .map(key -> beanContext.remove(key) != null);
    }
}
