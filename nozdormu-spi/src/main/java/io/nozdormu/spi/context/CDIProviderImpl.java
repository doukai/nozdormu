package io.nozdormu.spi.context;

import com.google.auto.service.AutoService;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.CDIProvider;

@AutoService(CDIProvider.class)
public class CDIProviderImpl implements CDIProvider {

    private final static CDI<Object> cdi = new CDIImpl<>();

    @Override
    public CDI<Object> getCDI() {
        return cdi;
    }
}
