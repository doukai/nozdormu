package io.nozdormu.spi.async;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

public final class Sync {

    public static <T> T await(Mono<T> a) {
        throw new RuntimeException("invoke await method with async implement");
    }

    public static <T> List<T> await(Flux<T> a) {
        throw new RuntimeException("invoke await method with async implement");
    }
}
