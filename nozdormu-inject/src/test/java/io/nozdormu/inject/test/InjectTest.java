package io.nozdormu.inject.test;

import io.nozdormu.inject.test.beans.Car;
import io.nozdormu.spi.context.BeanContext;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.util.UUID;

import static io.nozdormu.inject.test.context.RequestScopeInstanceFactory.REQUEST_ID;
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

        StepVerifier.create(car1.getBroadcast().get().contextWrite(Context.of(REQUEST_ID, UUID.randomUUID().toString())))
                .assertNext(broadcast -> assertEquals(broadcast.getName(), "BBC"))
                .expectComplete()
                .verify();
        StepVerifier.create(car1.getNavigation().get().contextWrite(Context.of(REQUEST_ID, UUID.randomUUID().toString())))
                .assertNext(navigation -> assertEquals(navigation.getName(), "Google"))
                .expectComplete()
                .verify();
    }
}
