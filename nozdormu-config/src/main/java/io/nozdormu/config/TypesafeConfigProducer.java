package io.nozdormu.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;

@ApplicationScoped
public class TypesafeConfigProducer {

    @ApplicationScoped
    @Produces
    public Config config() {
        return ConfigProviderResolver.instance().getConfig();
    }
}
