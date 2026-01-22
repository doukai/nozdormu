package io.nozdormu.inject.test.beans;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Produces;
import reactor.core.publisher.Mono;

@ApplicationScoped
public class AutoParts {

    public void event1(@Initialized(ApplicationScoped.class) @Observes Object event) {
        System.out.println("Broadcast received event1: " + event);
    }

    public Mono<Void> event2(@Initialized(ApplicationScoped.class) @Observes Object event) {
        System.out.println("Broadcast received event1: " + event);
        return Mono.empty();
    }

    public void event3(@Initialized(ApplicationScoped.class) @ObservesAsync Object event) {
        System.out.println("Broadcast received event1: " + event);
    }

    public Mono<Void> event4(@Initialized(ApplicationScoped.class) @ObservesAsync Object event) {
        System.out.println("Broadcast received event1: " + event);
        return Mono.empty();
    }

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
    public Navigation navigation() {
        return new Navigation();
    }
}
