package io.nozdormu.inject.test.beans;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

@Dependent
public class Car {

    public final Engine engine;
    public final Gearbox gearbox;
    public final Owner owner;
    public final Driver driver;

    @Inject
    public Car(Engine engine, Gearbox gearbox, Owner owner, Driver driver) {
        this.engine = engine;
        this.gearbox = gearbox;
        this.owner = owner;
        this.driver = driver;
    }

    public String getEngineName() {
        return engine.getEngineName();
    }

    public String getGearboxName() {
        return gearbox.getGearboxName();
    }

    public String getOwnerName() {
        return owner.getOwnerName();
    }

    public String getDriverName() {
        return driver.getDriverName();
    }
}
