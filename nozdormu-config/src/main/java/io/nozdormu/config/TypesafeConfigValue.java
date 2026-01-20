package io.nozdormu.config;

import org.eclipse.microprofile.config.ConfigValue;

public class TypesafeConfigValue implements ConfigValue {

    private final com.typesafe.config.ConfigValue configValue;

    public TypesafeConfigValue(com.typesafe.config.ConfigValue configValue) {
        this.configValue = configValue;
    }

    @Override
    public String getName() {
        String description = configValue.origin().description();
        return description != null ? description : "";
    }

    @Override
    public String getValue() {
        return configValue.render();
    }

    @Override
    public String getRawValue() {
        Object raw = configValue.unwrapped();
        return raw != null ? raw.toString() : null;
    }

    @Override
    public String getSourceName() {
        return configValue.origin().filename();
    }

    @Override
    public int getSourceOrdinal() {
        return configValue.origin().lineNumber();
    }
}
