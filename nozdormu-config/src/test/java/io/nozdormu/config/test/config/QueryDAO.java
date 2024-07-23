package io.nozdormu.config.test.config;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class QueryDAO {

    @ConfigProperty
    private DBConfig dbConfig;

    public void setDbConfig(DBConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    public String getDBInfo() {
        return dbConfig.toString();
    }

    public DBConfig getDbConfig() {
        return dbConfig;
    }
}
