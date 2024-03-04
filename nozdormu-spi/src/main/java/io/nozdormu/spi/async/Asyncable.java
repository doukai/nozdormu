package io.nozdormu.spi.async;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

public interface Asyncable {
    default <T> T await(T methodInvoke) {
        throw new RuntimeException("invoke await method with async implement");
    }

    default <T> T await(Mono<T> methodInvoke) {
        throw new RuntimeException("invoke await method with async implement");
    }

    default <T> List<T> await(Flux<T> methodInvoke) {
        throw new RuntimeException("invoke await method with async implement");
    }

    default <T> Mono<T> async(String methodName, Object... parameters) {
        return Mono.empty();
    }
}
