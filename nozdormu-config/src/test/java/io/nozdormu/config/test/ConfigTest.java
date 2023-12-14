package io.nozdormu.config.test;

import io.nozdormu.config.test.config.DBConfig;
import io.nozdormu.spi.context.BeanContext;
import org.junit.jupiter.api.Test;

public class ConfigTest {

    @Test
    void testDBConfig() {
        DBConfig dbConfig = BeanContext.get(DBConfig.class);
        System.out.println(dbConfig);
    }
}
