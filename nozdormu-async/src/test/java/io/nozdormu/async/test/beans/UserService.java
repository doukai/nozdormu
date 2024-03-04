package io.nozdormu.async.test.beans;

import io.nozdormu.spi.async.Async;
import io.nozdormu.spi.async.Asyncable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class UserService implements Asyncable {

    private final UserInfo userInfo;

    @Inject
    public UserService(UserInfo userInfo) {
        this.userInfo = userInfo;
    }

    @Async
    public User register(String name, int age) {
        User user = await(userInfo.getUser(name));
        user.setAge(age);
        return user;
    }

    @Async
    public String genPassword(User user) {
        List<Integer> passwords = await(userInfo.buildPassword(user.getEmail(), user.getName().length()));
        return passwords.stream().map(Object::toString).collect(Collectors.joining(""));
    }
}
