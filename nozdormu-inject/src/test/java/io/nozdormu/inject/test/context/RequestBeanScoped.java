package io.nozdormu.inject.test.context;

import io.nozdormu.spi.context.ReactorBeanScoped;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@ApplicationScoped
@Named("jakarta.enterprise.context.RequestScoped")
public class RequestBeanScoped implements ReactorBeanScoped {

    private final Map<String, Map<String, Object>> beanContext = new ConcurrentHashMap<>();

    public static final String REQUEST_ID = "requestId";

    @Override
    public Mono<String> getScopedKey() {
        return Mono.deferContextual(contextView -> Mono.justOrEmpty(contextView.getOrEmpty(REQUEST_ID)));
    }

    @SuppressWarnings("unchecked")
    public <T, R extends T> Mono<T> get(Class<T> beanClass, Supplier<R> supplier) {
        return getScopedKey()
                .mapNotNull(key -> {
                    if (key == null) {
                        return null;
                    }
                    Map<String, Object> scopedMap = beanContext.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
                    return (T) scopedMap.computeIfAbsent(beanClass.getName(), k -> supplier.get());
                });
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T, R extends T> Mono<T> getMono(Class<T> beanClass, Supplier<Mono<R>> supplier) {
        return getScopedKey()
                .flatMap(key -> {
                    if (key == null) {
                        return Mono.empty();
                    }
                    Map<String, Object> scopedMap = beanContext.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
                    return supplier.get().map(bean -> (T) scopedMap.computeIfAbsent(beanClass.getName(), k -> bean));
                });
    }

    @Override
    public <T, R extends T> Mono<Boolean> put(Class<T> beanClass, R bean) {
        return getScopedKey()
                .doOnSuccess(key ->
                        beanContext.computeIfAbsent(key, k -> new ConcurrentHashMap<>())
                                .put(beanClass.getName(), bean)
                )
                .map(beanContext::containsKey);
    }

    @Override
    public <T, R extends T> boolean put(String key, Class<T> beanClass, R bean) {
        beanContext.computeIfAbsent(key, k -> new ConcurrentHashMap<>())
                .put(beanClass.getName(), bean);
        return beanContext.containsKey(key);
    }

    @Override
    public Mono<Boolean> destroy() {
        return getScopedKey()
                .map(key -> beanContext.remove(key) != null);
    }

    @Override
    public boolean destroy(String key) {
        return beanContext.remove(key) != null;
    }
}
