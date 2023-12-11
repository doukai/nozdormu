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
        assertEquals(car1.getEngineName(), "V8 Engine");
        assertEquals(car1.getGearboxName(), "automatic");
        assertEquals(car1.getOwnerName(), car2.getOwnerName());
        assertNotEquals(car1.getDriverName(), car2.getDriverName());
    }
}
