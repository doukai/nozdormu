package io.nozdormu.config;

import com.google.auto.service.AutoService;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigBuilder;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;

@AutoService(ConfigProviderResolver.class)
public class TypesafeConfigProviderResolver extends ConfigProviderResolver {

    @Override
    public Config getConfig() {
        return new TypesafeConfig();
    }

    @Override
    public Config getConfig(ClassLoader loader) {
        return new TypesafeConfig(loader);
    }

    @Override
    public ConfigBuilder getBuilder() {
        return null;
    }

    @Override
    public void registerConfig(Config config, ClassLoader classLoader) {
    }

    @Override
    public void releaseConfig(Config config) {
    }
}
