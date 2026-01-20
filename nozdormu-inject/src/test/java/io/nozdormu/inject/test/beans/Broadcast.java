package io.nozdormu.inject.test.beans;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import reactor.core.publisher.Mono;

@RequestScoped
public class Broadcast {

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


    public String getName() {
        return "BBC";
    }
}
