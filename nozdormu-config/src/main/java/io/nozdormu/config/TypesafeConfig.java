package io.nozdormu.config;

import com.typesafe.config.*;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;
import org.tinylog.Logger;
import org.yaml.snakeyaml.Yaml;

import javax.annotation.processing.Filer;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TypesafeConfig implements Config {

    private Yaml yaml;

    public TypesafeConfig(ClassLoader classLoader) {
        Yaml yaml = new Yaml();
        Enumeration<URL> urlEnumeration = null;
        try {
            urlEnumeration = classLoader.getResources("");
            while (urlEnumeration.hasMoreElements()) {
                URL url = urlEnumeration.nextElement();
                URI uri = url.toURI();
                try (Stream<Path> pathStream = Files.list(Path.of(uri))) {
                    register(pathStream, application);
                } catch (FileSystemNotFoundException fileSystemNotFoundException) {
                    Map<String, String> env = new HashMap<>();
                    try (FileSystem fileSystem = FileSystems.newFileSystem(uri, env);
                         Stream<Path> pathStream = Files.list(fileSystem.getPath("META-INF/graphql"))) {
                        register(pathStream, application);
                    }
                }
            }
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public void register(Stream<Path> pathStream) {
        pathStream
                .filter(path ->
                        path.getFileName().toString().endsWith(".yml") ||
                                path.getFileName().toString().equals(".yaml")
                )
                .forEach(path -> {
                            try {
                                documentManager.getDocument().merge(path);
                                Logger.info("registered preset path {}", path);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                );
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
                    try {
                        merge(ConfigFactory.parseFile(configFile.toFile()));
                    } catch (ConfigException e) {
                        Logger.info("{} Ignored", e.origin().filename());
                    }
                }
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
