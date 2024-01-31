package io.nozdormu.spi.event;

import io.nozdormu.spi.context.BeanContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.Initialized;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.stream.Collectors;

public class ScopeEventResolver {

    private static final List<ScopeEvent> scopeEventList = BeanContext.getMap(ScopeEvent.class).values().stream()
            .sorted(Comparator.comparing(scopeEvent -> getPriority(scopeEvent.getClass())))
            .collect(Collectors.toList());

    public static Mono<Void> initialized(Class<? extends Annotation> scope) {
        return initialized(new HashMap<>(), scope);
    }

    public static Mono<Void> initialized(Map<String, Object> context, Class<? extends Annotation> scope) {
        return Flux.fromStream(
                        scopeEventList.stream()
                                .filter(scopeEvent -> scopeEvent.getClass().isAnnotationPresent(Initialized.class))
                                .filter(scopeEvent -> scopeEvent.getClass().getAnnotation(Initialized.class).value().equals(scope))
                )
                .concatMap(scopeEvent -> scopeEvent.fireAsync(context))
                .then();
    }

    public static Mono<Void> beforeDestroyed(Class<? extends Annotation> scope) {
        return beforeDestroyed(new HashMap<>(), scope);
    }

    public static Mono<Void> beforeDestroyed(Map<String, Object> context, Class<? extends Annotation> scope) {
        return Flux.fromStream(
                        scopeEventList.stream()
                                .filter(scopeEvent -> scopeEvent.getClass().isAnnotationPresent(BeforeDestroyed.class))
                                .filter(scopeEvent -> scopeEvent.getClass().getAnnotation(BeforeDestroyed.class).value().equals(scope))
                )
                .concatMap(scopeEvent -> scopeEvent.fireAsync(context))
                .then();
    }

    public static Mono<Void> destroyed(Class<? extends Annotation> scope) {
        return destroyed(new HashMap<>(), scope);
    }

    public static Mono<Void> destroyed(Map<String, Object> context, Class<? extends Annotation> scope) {
        return Flux.fromStream(
                        scopeEventList.stream()
                                .filter(scopeEvent -> scopeEvent.getClass().isAnnotationPresent(Destroyed.class))
                                .filter(scopeEvent -> scopeEvent.getClass().getAnnotation(Destroyed.class).value().equals(scope))
                )
                .concatMap(scopeEvent -> scopeEvent.fireAsync(context))
                .then();
    }

    private static int getPriority(Class<? extends ScopeEvent> type) {
        return Optional.ofNullable(type.getAnnotation(Priority.class)).map(Priority::value).orElse(Integer.MAX_VALUE);
    }
}
