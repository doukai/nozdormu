package io.nozdormu.async.test.beans;

import io.nozdormu.spi.async.Async;
import io.nozdormu.spi.async.Asyncable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class AsyncBean implements Asyncable {

    protected final UserInfo userInfo;

    @Inject
    public AsyncBean(UserInfo userInfo) {
        this.userInfo = userInfo;
    }

    @Async
    public String test1(String key) {
        String kai = await(userInfo.getWelcome(key));

        List<Double> doubleList = await(userInfo.genPass(kai.length()));

        return doubleList.stream().map(Object::toString).collect(Collectors.joining(","));
    }


    @Async
    public String test2(double d) {
        String kai = await(test1("a"));

        List<Double> doubleList = await(userInfo.genPass(kai.length()));

        return doubleList.stream().map(Object::toString).collect(Collectors.joining(","));
    }
}
