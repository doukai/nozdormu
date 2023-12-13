package io.nozdormu.interceptor.test.interceptor;

import io.nozdormu.interceptor.test.annotation.Launch;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

@ApplicationScoped
@Launch
@Priority(0)
@Interceptor
public class FirstLaunchInterceptor {

    @AroundInvoke
    public Object aroundInvoke(InvocationContext invocationContext) {
        try {
            return "first stage fired -> " + invocationContext.proceed();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
