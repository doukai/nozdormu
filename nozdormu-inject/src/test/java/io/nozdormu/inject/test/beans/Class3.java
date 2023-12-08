package io.nozdormu.inject.test.beans;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class Class3 {

    private final Class1 class1;
    private final Class2 class2;

    @Inject
    public Class3(Class1 class1, Class2 class2) {
        this.class1 = class1;
        this.class2 = class2;
    }
}
