package io.nozdormu.inject.test.beans;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

@ApplicationScoped
public class Owner {

    private final String name;

    public Owner() {
        name = "Mr." + UUID.randomUUID();
    }

    public String getOwnerName() {
        return name;
    }
}
