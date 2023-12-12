package io.nozdormu.inject.test.beans;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class Engine {

    public String getName(){
        return "V8 Engine";
    }
}
