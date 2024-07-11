package io.nozdormu.inject.test.beans;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

@ApplicationScoped
@Named("v12")
@Priority(2)
public class V12Engine implements IEngine {

    public String getName() {
        return "V12 Engine";
    }
}
