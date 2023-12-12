package io.nozdormu.inject.test.beans;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class Gearbox {

    public String getName() {
        return "automatic";
    }
}
