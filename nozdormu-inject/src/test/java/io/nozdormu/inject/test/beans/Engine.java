package io.nozdormu.inject.test.beans;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class Engine {

    public String getEngineName(){
        return "V8 Engine";
    }
}
