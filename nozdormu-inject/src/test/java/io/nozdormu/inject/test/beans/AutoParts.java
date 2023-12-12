package io.nozdormu.inject.test.beans;

import io.nozdormu.spi.context.BeanContext;
import io.nozdormu.spi.context.ScopeInstanceFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;
import reactor.core.publisher.Mono;

@ApplicationScoped
public class AutoParts {

    @ApplicationScoped
    @Produces
    public Brake brake() {
        return new Brake();
    }

    @ApplicationScoped
    @Produces
    public Wheel wheel(Brake brake) {
        return new Wheel(brake);
    }

    @RequestScoped
    @Produces
    public Mono<Navigation> navigation() {
        return BeanContext.get(ScopeInstanceFactory.class, RequestScoped.class.getName()).get(Navigation.class);
    }
}
