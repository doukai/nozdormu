package io.nozdormu.spi.event;

import io.nozdormu.spi.context.BeanContext;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.Initialized;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Map;

public final class ScopeEventPublisher {

    public static Mono<Void> initialized(Class<? extends Annotation> scope) {
        return initialized(new HashMap<>(), scope);
    }

    public static Mono<Void> initialized(Map<String, Object> context, Class<? extends Annotation> scope) {
        return Flux.merge(
                        Flux.fromIterable(BeanContext.getList(ScopeEventAsyncObserver.class, Map.of(Initialized.class.getName(), Map.of("value", scope))))
                                .flatMap(scopeEventAsyncObserver -> scopeEventAsyncObserver.onEventAsync(context)),
                        Flux.fromIterable(BeanContext.getList(ScopeEventObserver.class, Map.of(Initialized.class.getName(), Map.of("value", scope))))
                                .concatMap(scopeEventObserver -> scopeEventObserver.onEventAsync(context))
                )
                .then();
    }

    public static Mono<Void> beforeDestroyed(Class<? extends Annotation> scope) {
        return beforeDestroyed(new HashMap<>(), scope);
    }

    public static Mono<Void> beforeDestroyed(Map<String, Object> context, Class<? extends Annotation> scope) {
        return Flux.merge(
                        Flux.fromIterable(BeanContext.getList(ScopeEventAsyncObserver.class, Map.of(BeforeDestroyed.class.getName(), Map.of("value", scope))))
                                .flatMap(scopeEventAsyncObserver -> scopeEventAsyncObserver.onEventAsync(context)),
                        Flux.fromIterable(BeanContext.getList(ScopeEventObserver.class, Map.of(BeforeDestroyed.class.getName(), Map.of("value", scope))))
                                .concatMap(scopeEventObserver -> scopeEventObserver.onEventAsync(context))
                )
                .then();
    }

    public static Mono<Void> destroyed(Class<? extends Annotation> scope) {
        return destroyed(new HashMap<>(), scope);
    }

    public static Mono<Void> destroyed(Map<String, Object> context, Class<? extends Annotation> scope) {
        return Flux.merge(
                        Flux.fromIterable(BeanContext.getList(ScopeEventAsyncObserver.class, Map.of(Destroyed.class.getName(), Map.of("value", scope))))
                                .flatMap(scopeEventAsyncObserver -> scopeEventAsyncObserver.onEventAsync(context)),
                        Flux.fromIterable(BeanContext.getList(ScopeEventObserver.class, Map.of(Destroyed.class.getName(), Map.of("value", scope))))
                                .concatMap(scopeEventObserver -> scopeEventObserver.onEventAsync(context))
                )
                .then();
    }
}
