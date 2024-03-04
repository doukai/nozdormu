package io.nozdormu.async.test.beans;

import io.nozdormu.spi.async.Async;
import io.nozdormu.spi.async.Asyncable;
import jakarta.enterprise.context.ApplicationScoped;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ApplicationScoped
public class UserInfo implements Asyncable {

    public Mono<String> buildEmail(String name) {
        return Mono.just(name + "@nozdormu.com");
    }

    public Flux<Integer> buildPassword(String email, int size) {
        return Flux.range(0, size)
                .map(index -> (index + 1) * email.length());
    }

    @Async
    public User getUser(String name) {
        User user = new User();
        String email = await(buildEmail(name));
        user.setName(name);
        user.setEmail(email);
        return user;
    }
}
