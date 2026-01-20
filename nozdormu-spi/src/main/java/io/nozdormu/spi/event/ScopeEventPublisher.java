package io.nozdormu.spi.event;

import io.nozdormu.spi.context.BeanContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.Initialized;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class ScopeEventPublisher {

    public Mono<Void> initialized(Class<? extends Annotation> scope) {
        return initialized(new HashMap<>(), scope);
    }

    public Mono<Void> initialized(Map<String, Object> context, Class<? extends Annotation> scope) {
        return Flux
                .fromIterable(
                        BeanContext.getList(ScopeEventObserver.class, Map.of(Initialized.class.getName(), Map.of("value", scope)))
                )
                .concatMap(scopeEventObserver -> scopeEventObserver.onEventAsync(context))
                .then();
    }

    public Mono<Void> initializedAsync(Class<? extends Annotation> scope) {
        return initializedAsync(new HashMap<>(), scope);
    }

    public Mono<Void> initializedAsync(Map<String, Object> context, Class<? extends Annotation> scope) {
        return Flux
                .fromIterable(
                        BeanContext.getList(ScopeEventObserver.class, Map.of(Initialized.class.getName(), Map.of("value", scope)))
                )
                .flatMap(scopeEventObserver -> scopeEventObserver.onEventAsync(context))
                .then();
    }

    public Mono<Void> beforeDestroyed(Class<? extends Annotation> scope) {
        return beforeDestroyed(new HashMap<>(), scope);
    }

    public Mono<Void> beforeDestroyed(Map<String, Object> context, Class<? extends Annotation> scope) {
        return Flux
                .fromIterable(
                        BeanContext.getList(ScopeEventObserver.class, Map.of(BeforeDestroyed.class.getName(), Map.of("value", scope)))
                )
                .concatMap(scopeEventObserver -> scopeEventObserver.onEventAsync(context))
                .then();
    }

    public Mono<Void> beforeDestroyedAsync(Class<? extends Annotation> scope) {
        return beforeDestroyedAsync(new HashMap<>(), scope);
    }

    public Mono<Void> beforeDestroyedAsync(Map<String, Object> context, Class<? extends Annotation> scope) {
        return Flux
                .fromIterable(
                        BeanContext.getList(ScopeEventObserver.class, Map.of(BeforeDestroyed.class.getName(), Map.of("value", scope)))
                )
                .flatMap(scopeEventObserver -> scopeEventObserver.onEventAsync(context))
                .then();
    }

    public Mono<Void> destroyed(Class<? extends Annotation> scope) {
        return destroyed(new HashMap<>(), scope);
    }

    public Mono<Void> destroyed(Map<String, Object> context, Class<? extends Annotation> scope) {
        return Flux
                .fromIterable(
                        BeanContext.getList(ScopeEventObserver.class, Map.of(Destroyed.class.getName(), Map.of("value", scope)))
                )
                .concatMap(scopeEventObserver -> scopeEventObserver.onEventAsync(context))
                .then();
    }

    public Mono<Void> destroyedAsync(Class<? extends Annotation> scope) {
        return destroyedAsync(new HashMap<>(), scope);
    }

    public Mono<Void> destroyedAsync(Map<String, Object> context, Class<? extends Annotation> scope) {
        return Flux
                .fromIterable(
                        BeanContext.getList(ScopeEventObserver.class, Map.of(Destroyed.class.getName(), Map.of("value", scope)))
                )
                .flatMap(scopeEventObserver -> scopeEventObserver.onEventAsync(context))
                .then();
    }
}
