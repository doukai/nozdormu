package io.nozdormu.config;

import com.google.auto.service.AutoService;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigBuilder;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;
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
        return new SimpleConfigBuilder();
    }

    @Override
    public void registerConfig(Config config, ClassLoader classLoader) {
    }

    @Override
    public void releaseConfig(Config config) {
    }

    private static final class SimpleConfigBuilder implements ConfigBuilder {

        private ClassLoader classLoader;

        @Override
        public ConfigBuilder addDefaultSources() {
            return this;
        }

        @Override
        public ConfigBuilder addDiscoveredSources() {
            return this;
        }

        @Override
        public ConfigBuilder addDiscoveredConverters() {
            return this;
        }

        @Override
        public ConfigBuilder forClassLoader(ClassLoader classLoader) {
            this.classLoader = classLoader;
            return this;
        }

        @Override
        public ConfigBuilder withSources(ConfigSource... sources) {
            return this;
        }

        @Override
        public ConfigBuilder withConverters(Converter<?>... converters) {
            return this;
        }

        @Override
        public <T> ConfigBuilder withConverter(Class<T> type, int priority, Converter<T> converter) {
            return this;
        }

        @Override
        public Config build() {
            ClassLoader effectiveLoader = classLoader != null ? classLoader : Thread.currentThread().getContextClassLoader();
            return effectiveLoader != null ? new TypesafeConfig(effectiveLoader) : new TypesafeConfig();
        }
    }
}
