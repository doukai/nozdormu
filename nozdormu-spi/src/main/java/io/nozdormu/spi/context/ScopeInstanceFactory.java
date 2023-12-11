package io.nozdormu.spi.context;

import jakarta.inject.Provider;
import reactor.core.publisher.Mono;

public abstract class ScopeInstanceFactory {

    private ScopeInstanceFactory() {
    }

    public abstract Mono<ScopeInstances> getScopeInstances();

    public abstract <T, E extends T> Mono<T> computeIfAbsent(String id, Class<T> beanClass, String name, E instance);

    public abstract void invalidate(String id);

    public <T> Mono<T> get(Class<T> beanClass) {
        return get(beanClass, beanClass.getName());
    }

    @SuppressWarnings("unchecked")
    public <T> Mono<T> get(Class<T> beanClass, String name) {
        return getScopeInstances().mapNotNull(scopeInstances -> (T) scopeInstances.get(beanClass).get(name));
    }

    public <T> Mono<T> get(Class<T> beanClass, Provider<T> instanceProvider) {
        return get(beanClass, beanClass.getName(), instanceProvider);
    }

    @SuppressWarnings({"unchecked"})
    public <T> Mono<T> get(Class<T> beanClass, String name, Provider<T> instanceProvider) {
        return get(beanClass, name).switchIfEmpty(getScopeInstances().mapNotNull(scopeInstances -> (T) scopeInstances.get(beanClass).computeIfAbsent(name, key -> instanceProvider.get())));
    }

    public <T> Mono<T> getByMonoProvider(Class<T> beanClass, Provider<Mono<T>> instanceMonoProvider) {
        return getByMonoProvider(beanClass, beanClass.getName(), instanceMonoProvider);
    }

    @SuppressWarnings({"unchecked"})
    public <T> Mono<T> getByMonoProvider(Class<T> beanClass, String name, Provider<Mono<T>> instanceMonoProvider) {
        return get(beanClass, name).switchIfEmpty(getScopeInstances().flatMap(scopeInstances -> instanceMonoProvider.get().mapNotNull(instance -> (T) scopeInstances.get(beanClass).computeIfAbsent(name, key -> instance))));
    }

    @SuppressWarnings("unchecked")
    public <T> Mono<T> computeIfAbsent(T instance) {
        return computeIfAbsent((Class<T>) instance.getClass(), instance);
    }

    public <T, E extends T> Mono<T> computeIfAbsent(Class<T> beanClass, E instance) {
        return computeIfAbsent(beanClass, beanClass.getName(), instance);
    }

    @SuppressWarnings("unchecked")
    public <T, E extends T> Mono<T> computeIfAbsent(Class<T> beanClass, String name, E instance) {
        return getScopeInstances().mapNotNull(scopeInstances -> (T) scopeInstances.get(beanClass).computeIfAbsent(name, (key) -> instance));
    }

    @SuppressWarnings("unchecked")
    public <T> Mono<T> computeIfAbsent(String id, T instance) {
        return computeIfAbsent(id, (Class<T>) instance.getClass(), instance);
    }

    public <T, E extends T> Mono<T> computeIfAbsent(String id, Class<T> beanClass, E instance) {
        return computeIfAbsent(id, beanClass, beanClass.getName(), instance);
    }
}
