package io.nozdormu.config.test;

import io.nozdormu.config.test.config.DBConfig;
import io.nozdormu.config.test.config.QueryDAO;
import io.nozdormu.spi.context.BeanContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ConfigTest {

    @Test
    void testDBConfig() {
        QueryDAO queryDAO = BeanContext.get(QueryDAO.class);
        DBConfig dbConfig = queryDAO.getDbConfig();
        assertEquals(dbConfig.getHost(), "127.0.0.1");
        assertEquals(dbConfig.getPort(), 3306);
        assertEquals(dbConfig.getUser(), "root");
        assertEquals(dbConfig.getPassword(), "pass");
        assertNull(dbConfig.getDb());
    }
}
