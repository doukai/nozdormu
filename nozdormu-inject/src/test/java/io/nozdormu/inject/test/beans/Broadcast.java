package io.nozdormu.inject.test.beans;

import jakarta.enterprise.context.RequestScoped;

@RequestScoped
public class Broadcast {

    public String getName() {
        return "BBC";
    }
}
