package io.nozdormu.inject.test.context;

import io.nozdormu.spi.context.ReactorBeanScoped;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import reactor.core.publisher.Mono;

@ApplicationScoped
@Named("jakarta.enterprise.context.RequestScoped")
public class RequestScopeInstanceFactory extends ReactorBeanScoped {

    public static final String REQUEST_ID = "requestId";

    @Override
    protected Mono<String> getScopedKey() {
        return Mono.deferContextual(contextView -> Mono.justOrEmpty(contextView.getOrEmpty(REQUEST_ID)));
    }
}
