package io.nozdormu.spi.context;

import jakarta.inject.Named;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.Optional;

public class PublisherBeanContext {

    private static final String SCOPE_INSTANCES_KEY = "scopeInstances";

    public static <T> Mono<T> get(Class<T> beanClass) {
        return get(beanClass, beanClass.getName());
    }

    @SuppressWarnings("unchecked")
    public static <T> Mono<T> get(Class<T> beanClass, String name) {
        return Mono.deferContextual(contextView ->
                Mono.justOrEmpty(
                        contextView.getOrEmpty(SCOPE_INSTANCES_KEY)
                                .map(scopeInstances -> (ScopeInstances) scopeInstances)
                                .flatMap(scopeInstances -> Optional.ofNullable(scopeInstances.get(beanClass)))
                                .flatMap(map -> Optional.ofNullable(map.get(name)))
                                .map(bean -> (T) bean)
                )
        );
    }

    public static <T> Mono<T> compute(Class<T> beanClass, Object object) {
        return compute(beanClass, beanClass.getName(), object);
    }

    @SuppressWarnings("unchecked")
    public static <T> Mono<T> compute(Class<T> beanClass, String name, Object object) {
        return Mono
                .deferContextual(contextView ->
                        Mono.justOrEmpty(contextView.getOrEmpty(SCOPE_INSTANCES_KEY))
                )
                .switchIfEmpty(
                        Mono
                                .deferContextual(contextView ->
                                        Mono.just(contextView.get(SCOPE_INSTANCES_KEY))
                                )
                                .contextWrite(Context.of(SCOPE_INSTANCES_KEY, new ScopeInstances()))
                )
                .map(scopeInstances -> (ScopeInstances) scopeInstances)
                .map(scopeInstances -> (T) scopeInstances.get(beanClass).compute(name, (k, v) -> object));
    }

    public static Context of(Class<?> class1, Object bean1) {
        ScopeInstances scopeInstances = new ScopeInstances();
        scopeInstances.get(class1).put(class1.getName(), bean1);
        if (class1.isAnnotationPresent(Named.class)) {
            scopeInstances.get(bean1.getClass()).put(class1.getAnnotation(Named.class).value(), bean1);
        }
        return Context.of(SCOPE_INSTANCES_KEY, scopeInstances);
    }

    public static Context of(Class<?> class1, Object bean1, Class<?> class2, Object bean2) {
        ScopeInstances scopeInstances = new ScopeInstances();
        scopeInstances.get(class1).put(class1.getName(), bean1);
        if (class1.isAnnotationPresent(Named.class)) {
            scopeInstances.get(bean1.getClass()).put(class1.getAnnotation(Named.class).value(), bean1);
        }
        scopeInstances.get(class2).put(class2.getName(), bean2);
        if (class2.isAnnotationPresent(Named.class)) {
            scopeInstances.get(bean2.getClass()).put(class2.getAnnotation(Named.class).value(), bean2);
        }
        return Context.of(SCOPE_INSTANCES_KEY, scopeInstances);
    }

    public static Context of(Class<?> class1, Object bean1, Class<?> class2, Object bean2, Class<?> class3, Object bean3) {
        ScopeInstances scopeInstances = new ScopeInstances();
        scopeInstances.get(class1).put(class1.getName(), bean1);
        if (class1.isAnnotationPresent(Named.class)) {
            scopeInstances.get(bean1.getClass()).put(class1.getAnnotation(Named.class).value(), bean1);
        }
        scopeInstances.get(class2).put(class2.getName(), bean2);
        if (class2.isAnnotationPresent(Named.class)) {
            scopeInstances.get(bean2.getClass()).put(class2.getAnnotation(Named.class).value(), bean2);
        }
        scopeInstances.get(class3).put(class3.getName(), bean3);
        if (class3.isAnnotationPresent(Named.class)) {
            scopeInstances.get(bean3.getClass()).put(class3.getAnnotation(Named.class).value(), bean3);
        }
        return Context.of(SCOPE_INSTANCES_KEY, scopeInstances);
    }

    public static Context of(Class<?> class1, Object bean1, Class<?> class2, Object bean2, Class<?> class3, Object bean3, Class<?> class4, Object bean4) {
        ScopeInstances scopeInstances = new ScopeInstances();
        scopeInstances.get(class1).put(class1.getName(), bean1);
        if (class1.isAnnotationPresent(Named.class)) {
            scopeInstances.get(bean1.getClass()).put(class1.getAnnotation(Named.class).value(), bean1);
        }
        scopeInstances.get(class2).put(class2.getName(), bean2);
        if (class2.isAnnotationPresent(Named.class)) {
            scopeInstances.get(bean2.getClass()).put(class2.getAnnotation(Named.class).value(), bean2);
        }
        scopeInstances.get(class3).put(class3.getName(), bean3);
        if (class3.isAnnotationPresent(Named.class)) {
            scopeInstances.get(bean3.getClass()).put(class3.getAnnotation(Named.class).value(), bean3);
        }
        scopeInstances.get(class4).put(class4.getName(), bean4);
        if (class4.isAnnotationPresent(Named.class)) {
            scopeInstances.get(bean4.getClass()).put(class4.getAnnotation(Named.class).value(), bean4);
        }
        return Context.of(SCOPE_INSTANCES_KEY, scopeInstances);
    }

    public static Context of(Class<?> class1, Object bean1, Class<?> class2, Object bean2, Class<?> class3, Object bean3, Class<?> class4, Object bean4, Class<?> class5, Object bean5) {
        ScopeInstances scopeInstances = new ScopeInstances();
        scopeInstances.get(class1).put(class1.getName(), bean1);
        if (class1.isAnnotationPresent(Named.class)) {
            scopeInstances.get(bean1.getClass()).put(class1.getAnnotation(Named.class).value(), bean1);
        }
        scopeInstances.get(class2).put(class2.getName(), bean2);
        if (class2.isAnnotationPresent(Named.class)) {
            scopeInstances.get(bean2.getClass()).put(class2.getAnnotation(Named.class).value(), bean2);
        }
        scopeInstances.get(class3).put(class3.getName(), bean3);
        if (class3.isAnnotationPresent(Named.class)) {
            scopeInstances.get(bean3.getClass()).put(class3.getAnnotation(Named.class).value(), bean3);
        }
        scopeInstances.get(class4).put(class4.getName(), bean4);
        if (class4.isAnnotationPresent(Named.class)) {
            scopeInstances.get(bean4.getClass()).put(class4.getAnnotation(Named.class).value(), bean4);
        }
        scopeInstances.get(class5).put(class5.getName(), bean5);
        if (class5.isAnnotationPresent(Named.class)) {
            scopeInstances.get(bean5.getClass()).put(class5.getAnnotation(Named.class).value(), bean5);
        }
        return Context.of(SCOPE_INSTANCES_KEY, scopeInstances);
    }
}
