package io.nozdormu.inject.test.beans;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import reactor.core.publisher.Mono;

@Dependent
public class Car {

    private final Engine engine;
    private Gearbox gearbox;
    @Inject
    private Owner owner;
    private final Provider<Driver> driver;
    @Inject
    private Wheel wheel;
    @Inject
    private Provider<Mono<Navigation>> navigation;
    @Inject
    private Provider<Mono<Broadcast>> broadcast;

    @Inject
    public void setGearbox(Gearbox gearbox) {
        this.gearbox = gearbox;
    }

    public void setOwner(Owner owner) {
        this.owner = owner;
    }

    public void setWheel(Wheel wheel) {
        this.wheel = wheel;
    }

    public void setNavigation(Provider<Mono<Navigation>> navigation) {
        this.navigation = navigation;
    }

    public void setBroadcast(Provider<Mono<Broadcast>> broadcast) {
        this.broadcast = broadcast;
    }

    @Inject
    public Car(Engine engine, Provider<Driver> driver) {
        this.engine = engine;
        this.driver = driver;
    }

    public Engine getEngine() {
        return engine;
    }

    public Gearbox getGearbox() {
        return gearbox;
    }

    public Owner getOwner() {
        return owner;
    }

    public Driver getDriver() {
        return driver.get();
    }

    public Wheel getWheel() {
        return wheel;
    }

    public Provider<Mono<Navigation>> getNavigation() {
        return navigation;
    }

    public Provider<Mono<Broadcast>> getBroadcast() {
        return broadcast;
    }
}
