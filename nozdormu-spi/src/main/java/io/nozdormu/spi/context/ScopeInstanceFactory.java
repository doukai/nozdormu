package io.nozdormu.spi.context;

import jakarta.inject.Provider;
import reactor.core.publisher.Mono;

public abstract class ScopeInstanceFactory {

    public abstract Mono<ScopeInstances> getScopeInstances();

    public abstract <T, E extends T> Mono<T> compute(String id, Class<T> beanClass, String name, E instance);

    public abstract void invalidate(String id);

    public <T> Mono<T> get(Class<T> beanClass) {
        return get(beanClass, beanClass.getName());
    }

    @SuppressWarnings("unchecked")
    public <T> Mono<T> get(Class<T> beanClass, String name) {
        return getScopeInstances()
                .mapNotNull(scopeInstances -> (T) scopeInstances.get(beanClass).get(name));
    }

    public <T> Mono<T> get(Class<T> beanClass, Provider<Mono<T>> instanceProvider) {
        return get(beanClass, beanClass.getName(), instanceProvider);
    }

    @SuppressWarnings({"unchecked"})
    public <T> Mono<T> get(Class<T> beanClass, String name, Provider<Mono<T>> instanceProvider) {
        return get(beanClass, name)
                .switchIfEmpty(
                        instanceProvider.get()
                                .flatMap(instance ->
                                        getScopeInstances()
                                                .mapNotNull(scopeInstances -> (T) scopeInstances.get(beanClass).compute(name, (k, v) -> instance))
                                )
                );
    }

    @SuppressWarnings("unchecked")
    public <T> Mono<T> compute(T instance) {
        return compute((Class<T>) instance.getClass(), instance);
    }

    public <T, E extends T> Mono<T> compute(Class<T> beanClass, E instance) {
        return compute(beanClass, beanClass.getName(), instance);
    }

    @SuppressWarnings("unchecked")
    public <T, E extends T> Mono<T> compute(Class<T> beanClass, String name, E instance) {
        return getScopeInstances()
                .mapNotNull(scopeInstances -> (T) scopeInstances.get(beanClass).compute(name, (k, v) -> instance));
    }

    @SuppressWarnings("unchecked")
    public <T> Mono<T> compute(String id, T instance) {
        return compute(id, (Class<T>) instance.getClass(), instance);
    }

    public <T, E extends T> Mono<T> compute(String id, Class<T> beanClass, E instance) {
        return compute(id, beanClass, beanClass.getName(), instance);
    }
}
