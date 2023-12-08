package io.nozdormu.inject.test.beans;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

@Dependent
public class Class4 {

    private final Class1 class1;
    private final Class2 class2;
    private final Class3 class3;

    @Inject
    public Class4(Class1 class1, Class2 class2, Class3 class3) {
        this.class1 = class1;
        this.class2 = class2;
        this.class3 = class3;
    }
}
