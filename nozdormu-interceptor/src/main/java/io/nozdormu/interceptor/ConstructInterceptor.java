package io.nozdormu.interceptor;

import jakarta.interceptor.InvocationContext;

public interface ConstructInterceptor {

    InvocationContext buildContext();

    Object aroundConstruct(InvocationContext invocationContext);
}
