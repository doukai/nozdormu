package io.nozdormu.inject.test;

import io.nozdormu.inject.test.beans.Car;
import io.nozdormu.spi.context.BeanContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class InjectTest {

    @Test
    void testCar() {
        Car car1 = BeanContext.get(Car.class);
        Car car2 = BeanContext.get(Car.class);
        assertEquals(car1.getEngine().getName(), "V8 Engine");
        assertEquals(car1.getGearbox().getName(), "automatic");
        assertEquals(car1.getOwner().getName(), car2.getOwner().getName());
        assertNotEquals(car1.getDriver().getName(), car2.getDriver().getName());
        assertEquals(car1.getWheel().getSize(), 48);
        assertEquals(car1.getBroadcast().get().block().getName(), "BBC");
        assertEquals(car1.getNavigation().get().block().getName(), "Google");
    }
}
