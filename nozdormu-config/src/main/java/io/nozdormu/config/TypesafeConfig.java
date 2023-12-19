package io.nozdormu.config;

import com.typesafe.config.*;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;
import org.tinylog.Logger;

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
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TypesafeConfig implements Config {

    private com.typesafe.config.Config config;

    public TypesafeConfig() {
        this.config = ConfigFactory.load(ConfigParseOptions.defaults());
    }

    public TypesafeConfig(com.typesafe.config.Config config) {
        this.config = config;
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
        return config.entrySet().stream().map(Map.Entry::getKey).collect(Collectors.toList());
    }

    @Override
    public Iterable<ConfigSource> getConfigSources() {
        return null;
    }

    @Override
    public <T> Optional<Converter<T>> getConverter(Class<T> forType) {
        return Optional.of(new TypesafeConverter<>(forType));
    }

    @Override
    public <T> T unwrap(Class<T> type) {
        return null;
    }

    public TypesafeConfig merge(com.typesafe.config.Config config) {
        this.config = this.config.withFallback(config);
        return this;
    }

    public TypesafeConfig load(ClassLoader classLoader) {
        this.config = ConfigFactory.load(classLoader, ConfigParseOptions.defaults());
        return this;
    }

    public TypesafeConfig merge(ClassLoader classLoader) {
        merge(ConfigFactory.load(classLoader, ConfigParseOptions.defaults()));
        return this;
    }

    public TypesafeConfig load(String path) {
        this.config = ConfigFactory.empty();
        return merge(path);
    }

    public TypesafeConfig merge(String path) {
        Path configPath = Paths.get(path);
        if (Files.exists(configPath)) {
            try (Stream<Path> fileList = Files.list(configPath)) {
                List<Path> configFileList = fileList
                        .filter(filePath -> filePath.toString().endsWith(".conf") || filePath.toString().endsWith(".json") || filePath.toString().endsWith(".properties"))
                        .collect(Collectors.toList());
                for (Path configFile : configFileList) {
                    merge(ConfigFactory.parseFile(configFile.toFile()));
                }
            } catch (ConfigException ignored) {
            } catch (IOException e) {
                Logger.error(e);
            }
        }
        return this;
    }

    public TypesafeConfig load(Filer filer) {
        this.config = ConfigFactory.empty();
        Path generatedSourcePath = getGeneratedSourcePath(filer);
        merge(getResourcesPath(generatedSourcePath).toString());
        merge(getTestResourcesPath(generatedSourcePath).toString());
        return this;
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
            Logger.info("generated source path: {}", generatedSourcePath.toString());
            return generatedSourcePath;
        } catch (IOException e) {
            Logger.error(e);
            throw new RuntimeException(e);
        }
    }

    private Path getResourcesPath(Path generatedSourcePath) {
        Path sourcePath = generatedSourcePath.getParent().getParent().getParent().getParent().getParent().getParent().resolve("src/main/resources");
        Logger.info("resources path: {}", sourcePath.toString());
        return sourcePath;
    }

    private Path getTestResourcesPath(Path generatedSourcePath) {
        Path sourcePath = generatedSourcePath.getParent().getParent().getParent().getParent().getParent().getParent().resolve("src/test/resources");
        Logger.info("test resources path: {}", sourcePath.toString());
        return sourcePath;
    }
}
