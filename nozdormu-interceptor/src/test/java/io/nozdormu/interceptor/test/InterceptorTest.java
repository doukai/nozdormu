package io.nozdormu.interceptor.test;

import io.nozdormu.interceptor.test.beans.Satellite;
import io.nozdormu.spi.context.BeanContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class InterceptorTest {

    @Test
    void testSatellite() {
        Satellite satellite = BeanContext.get(Satellite.class);
        assertEquals(satellite.checkResult(), "first stage ready -> second stage ready -> all check ready, fire");
        assertEquals(satellite.startup("nozdormu"), "first stage fired -> second stage fired -> hello nozdormu I am NASA");
    }
}
