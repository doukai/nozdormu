package io.nozdormu.inject.test.context;

import io.nozdormu.spi.context.ScopeInstanceFactory;
import io.nozdormu.spi.context.ScopeInstances;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
@Named("jakarta.enterprise.context.RequestScoped")
public class RequestScopeInstanceFactory extends ScopeInstanceFactory {
    public static final String REQUEST_ID = "requestId";
    private static final Map<String, ScopeInstances> REQUEST_CACHE = new HashMap<>();

    @Override
    public Mono<ScopeInstances> getScopeInstances() {
        return Mono.deferContextual(contextView -> Mono.justOrEmpty(contextView.getOrEmpty(REQUEST_ID)).map(id -> REQUEST_CACHE.computeIfAbsent((String) id, (key) -> new ScopeInstances())));
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T, E extends T> Mono<T> compute(String id, Class<T> beanClass, String name, E instance) {
        return Mono.just((T) REQUEST_CACHE.get(id).get(beanClass).compute(name, (k, v) -> instance));
    }

    @Override
    public void invalidate(String id) {
        REQUEST_CACHE.remove(id);
    }
}
