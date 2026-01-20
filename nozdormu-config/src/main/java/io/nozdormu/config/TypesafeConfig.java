package io.nozdormu.config;

import com.typesafe.config.*;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.processing.Filer;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.Optional;
import java.util.stream.Stream;

public class TypesafeConfig implements Config {

    private static final Logger logger = LoggerFactory.getLogger(TypesafeConfig.class);

    private com.typesafe.config.Config config;

    public TypesafeConfig() {
        this.config = ConfigFactory.load(ConfigParseOptions.defaults());
    }

    public TypesafeConfig(ClassLoader classLoader) {
        this.config = ConfigFactory.load(classLoader);
    }

    @Override
    public <T> T getValue(String propertyName, Class<T> propertyType) {
        return ConfigBeanFactory.create(config.getConfig(propertyName), propertyType);
    }

    @Override
    public ConfigValue getConfigValue(String propertyName) {
        return new TypesafeConfigValue(config.getValue(propertyName));
    }

    @Override
    public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
        if (config.hasPath(propertyName)) {
            return Optional.of(getValue(propertyName, propertyType));
        }
        return Optional.empty();
    }

    @Override
    public Iterable<String> getPropertyNames() {
        return config.root().keySet();
    }

    @Override
    public Iterable<ConfigSource> getConfigSources() {
        return List.of();
    }

    @Override
    public <T> Optional<Converter<T>> getConverter(Class<T> forType) {
        return Optional.of(new TypesafeConverter<>(forType));
    }

    @Override
    public <T> T unwrap(Class<T> type) {
        if (type.isInstance(config)) {
            return type.cast(config);
        }
        throw new IllegalArgumentException("Unsupported unwrap type: " + type.getName());
    }

    public void merge(Stream<Path> pathStream) {
        pathStream
                .filter(path -> path.toString().endsWith(".conf") || path.toString().endsWith(".json") || path.toString().endsWith(".properties"))
                .forEach(path -> {
                            try {
                                this.config = this.config.withFallback(ConfigFactory.parseFile(path.toFile()));
                            } catch (ConfigException e) {
                                logger.info("{} Ignored", e.origin().filename());
                            }
                        }
                );
    }

    public void merge(Path path) {
        if (Files.exists(path)) {
            try (Stream<Path> pathStream = Files.list(path)) {
                merge(pathStream);
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    public TypesafeConfig merge(String pathName) {
        merge(Paths.get(pathName));
        return this;
    }

    public TypesafeConfig merge(Filer filer) {
        Path generatedSourcePath = getGeneratedSourcePath(filer);
        merge(getResourcesPath(generatedSourcePath));
        merge(getTestResourcesPath(generatedSourcePath));
        return this;
    }

    public TypesafeConfig load(String pathName) {
        this.config = ConfigFactory.empty();
        return merge(pathName);
    }

    public TypesafeConfig load(Filer filer) {
        this.config = ConfigFactory.empty();
        return merge(filer);
    }

    private Path getGeneratedSourcePath(Filer filer) {
        try {
            FileObject tmp = filer.createResource(StandardLocation.SOURCE_OUTPUT, "", UUID.randomUUID().toString());
            Writer writer = tmp.openWriter();
            writer.write("");
            writer.close();
            Path path = Paths.get(tmp.toUri());
            Files.deleteIfExists(path);
            Path generatedSourcePath = path.getParent();
            logger.info("generated source path: {}", generatedSourcePath.toString());
            return generatedSourcePath;
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private Path getResourcesPath(Path generatedSourcePath) {
        Path sourcePath = generatedSourcePath.getParent().getParent().getParent().getParent().getParent().getParent().resolve("src/main/resources");
        logger.info("resources path: {}", sourcePath);
        return sourcePath;
    }

    private Path getTestResourcesPath(Path generatedSourcePath) {
        Path sourcePath = generatedSourcePath.getParent().getParent().getParent().getParent().getParent().getParent().resolve("src/test/resources");
        logger.info("test resources path: {}", sourcePath);
        return sourcePath;
    }
}
