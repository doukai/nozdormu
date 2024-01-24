package io.nozdormu.spi.event;

import reactor.core.publisher.Mono;

import java.util.Map;

public interface ScopeEvent {

    default void fire(Map<String, Object> context) {
        fireAsync(context).block();
    }

    default Mono<Void> fireAsync(Map<String, Object> context) {
        return Mono.fromRunnable(() -> fire(context));
    }
}
