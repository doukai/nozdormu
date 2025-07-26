package io.nozdormu.spi.async;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface Asyncable {

    String ASYNC_METHOD_NAME_SUFFIX = "Async";

    default void await(Runnable runnable) {
        throw new RuntimeException("invoke await method with async implement");
    }

    default <T> T await(T methodInvoke) {
        throw new RuntimeException("invoke await method with async implement");
    }

    default <T> T await(Mono<T> methodInvoke) {
        throw new RuntimeException("invoke await method with async implement");
    }

    default <T> List<T> await(Flux<T> methodInvoke) {
        throw new RuntimeException("invoke await method with async implement");
    }

    default <T> Mono<T> async(String asyncMethodName, Object... parameters) {
        throw new RuntimeException("invoke async method with async implement");
    }

    default <T> Mono<T> asyncInvoke(String methodName, Object... parameters) {
        String asyncMethodName = Stream
                .concat(
                        Stream.of(methodName + ASYNC_METHOD_NAME_SUFFIX),
                        Arrays.stream(parameters).map(parameter -> parameter.getClass().getSimpleName())
                )
                .collect(Collectors.joining("_"));
        return async(asyncMethodName, parameters);
    }
}
