package io.nozdormu.spi.event;

import reactor.core.publisher.Mono;

public interface ScopeEventAsyncObserver {

    default void onEvent(Object event) {
    }

    default Mono<Void> onEventAsync(Object event) {
        return Mono.fromRunnable(() -> onEvent(event));
    }
}
