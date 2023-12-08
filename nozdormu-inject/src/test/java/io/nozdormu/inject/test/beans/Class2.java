package io.nozdormu.inject.test.beans;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

@Dependent
public class Class2 {

    private final Class1 class1;

    @Inject
    public Class2(Class1 class1) {
        this.class1 = class1;
    }
}
