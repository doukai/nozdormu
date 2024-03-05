package io.nozdormu.async.test;

import io.nozdormu.async.test.beans.User;
import io.nozdormu.async.test.beans.UserService;
import io.nozdormu.spi.context.BeanContext;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AsyncTest {

    @Test
    void testUser() {
        UserService userService = BeanContext.get(UserService.class);
        Mono<User> userMono = userService.asyncInvoke("register", "nozdormu", 6);
        StepVerifier.create(userMono)
                .assertNext(user -> assertEquals(user.getEmail(), "nozdormu@nozdormu.com"))
                .expectComplete()
                .verify();

        String name = "kai";
        String email = "kai@nozdormu.com";
        String target = IntStream.range(0, name.length())
                .mapToObj(index -> "" + (index + 1) * email.length())
                .collect(Collectors.joining(""));
        User user = new User();
        user.setName(name);

        Mono<String> genPassword = userService.asyncInvoke("genPassword", user);
        StepVerifier.create(genPassword)
                .assertNext(password -> assertEquals(password, target))
                .expectComplete()
                .verify();
    }
}
