package io.nozdormu.spi.decompiler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

public interface TypeElementDecompilerProvider {

    Logger logger = LoggerFactory.getLogger(TypeElementDecompilerProvider.class);

    String DEFAULT_PROVIDER = "io.nozdormu.decompiler.ProcyonDecompiler";

    TypeElementDecompiler create(ClassLoader classLoader);

    static TypeElementDecompiler load(ClassLoader classLoader) {
        logger.info("load TypeElementDecompiler from {}", classLoader.getName());
        List<TypeElementDecompilerProvider> services = new ArrayList<>();
        ServiceLoader<TypeElementDecompilerProvider> loader = ServiceLoader.load(TypeElementDecompilerProvider.class, classLoader);
        loader.forEach(services::add);
        if (services.size() > 1) {
            for (TypeElementDecompilerProvider provider : services) {
                if (!provider.getClass().getName().equals(DEFAULT_PROVIDER)) {
                    return provider.create(classLoader);
                }
            }
        } else if (!services.isEmpty()) {
            return services.get(0).create(classLoader);
        }
        throw new RuntimeException("default TypeElementDecompilerProvider not found");
    }
}
