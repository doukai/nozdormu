package io.nozdormu.config.test;

import io.nozdormu.config.test.config.DBConfig;
import io.nozdormu.spi.context.BeanContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ConfigTest {

    @Test
    void testDBConfig() {
        DBConfig dbConfig = BeanContext.get(DBConfig.class);
        assertEquals(dbConfig.getHost(), "127.0.0.1");
        assertEquals(dbConfig.getPort(), 3306);
        assertEquals(dbConfig.getUser(), "root");
        assertEquals(dbConfig.getPassword(), "pass");
    }
}
