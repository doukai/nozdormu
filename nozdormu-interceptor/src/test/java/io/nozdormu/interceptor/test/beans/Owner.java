package io.nozdormu.interceptor.test.beans;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class Owner {

    private final String name;

    public Owner() {
        name = "NASA";
    }

    public String getName() {
        return name;
    }
}
