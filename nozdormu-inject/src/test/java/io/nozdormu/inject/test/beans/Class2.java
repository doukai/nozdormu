package io.nozdormu.inject.test.beans;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

@Dependent
public class Class2 {

    private final Engine class1;

    @Inject
    public Class2(Engine class1) {
        this.class1 = class1;
    }
}
