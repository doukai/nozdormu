package io.nozdormu.config.produces;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigBuilder;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;

@ApplicationScoped
public class TypesafeConfigProducer {

    private static final ConfigProviderResolver configProviderResolver = ConfigProviderResolver.instance();

    @ApplicationScoped
    @Produces
    public Config config() {
        return configProviderResolver.getConfig();
    }

    @ApplicationScoped
    @Produces
    public ConfigBuilder configBuilder() {
        return configProviderResolver.getBuilder();
    }
}
