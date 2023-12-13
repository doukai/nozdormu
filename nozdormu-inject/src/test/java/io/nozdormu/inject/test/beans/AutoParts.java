package io.nozdormu.inject.test.beans;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class AutoParts {

    @ApplicationScoped
    @Produces
    public Brake brake() {
        return new Brake();
    }

    @ApplicationScoped
    @Produces
    public Wheel wheel(Brake brake) {
        return new Wheel(brake);
    }

    @RequestScoped
    @Produces
    public Navigation navigation() {
        return new Navigation();
    }
}
