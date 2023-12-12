package io.nozdormu.inject.test.beans;

import jakarta.enterprise.context.Dependent;

import java.util.UUID;

@Dependent
public class Driver {

    private final String name;

    public Driver() {
        name = "Mr." + UUID.randomUUID();
    }

    public String getName() {
        return name;
    }
}
