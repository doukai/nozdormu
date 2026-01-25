package io.nozdormu.spi.context;

import reactor.core.publisher.Mono;

import java.util.function.Supplier;

public interface ReactorBeanScoped {

    Mono<String> getScopedKey();

    <T> Mono<T> get(Class<T> beanClass);

    <T, R extends T> Mono<T> get(Class<T> beanClass, Supplier<R> supplier);

    <T, R extends T> Mono<T> getMono(Class<T> beanClass, Supplier<Mono<R>> supplier);

    <T, R extends T> Mono<Boolean> put(Class<T> beanClass, R bean);

    <T, R extends T> boolean put(String key, Class<T> beanClass, R bean);

    Mono<Boolean> destroy();

    boolean destroy(String key);
}
