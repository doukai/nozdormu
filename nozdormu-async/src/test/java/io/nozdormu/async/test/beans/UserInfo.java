package io.nozdormu.async.test.beans;

import jakarta.enterprise.context.ApplicationScoped;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ApplicationScoped
public class UserInfo {

    public String welcome = "hello ";


    public Mono<String> getWelcome(String name) {
        return Mono.just(welcome + name);
    }


    public Flux<Double> genPass(int seed) {
        return Flux.just(Math.random() / seed, Math.random() / seed, Math.random() / seed);
    }

}
