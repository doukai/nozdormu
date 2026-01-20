package io.nozdormu.spi.event;

import reactor.core.publisher.Mono;

public interface ScopeEventAsyncObserver {

    default void onEvent(Object context) {
    }

    default Mono<Void> onEventAsync(Object context) {
        return Mono.fromRunnable(() -> onEvent(context));
    }
}
