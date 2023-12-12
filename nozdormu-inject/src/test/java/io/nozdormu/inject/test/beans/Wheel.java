package io.nozdormu.inject.test.beans;

public class Wheel {

    private final Brake brake;

    private final Integer size;

    public Wheel(Brake brake) {
        this.brake = brake;
        this.size = 48;
    }

    public Brake getBrake() {
        return brake;
    }

    public Integer getSize() {
        return size;
    }
}
