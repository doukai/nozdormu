package io.nozdormu.interceptor;

import jakarta.interceptor.InvocationContext;

public interface InvokeInterceptor {

    InvocationContext buildContext();

    Object aroundInvoke(InvocationContext invocationContext);
}
