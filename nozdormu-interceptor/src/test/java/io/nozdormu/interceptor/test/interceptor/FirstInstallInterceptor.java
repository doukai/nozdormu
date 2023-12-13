package io.nozdormu.interceptor.test.interceptor;

import io.nozdormu.interceptor.test.annotation.Install;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.interceptor.AroundConstruct;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
@Install
@Priority(0)
@Interceptor
public class FirstInstallInterceptor {

    @SuppressWarnings("unchecked")
    @AroundConstruct
    public Object aroundConstruct(InvocationContext invocationContext) {
        List<String> infoList = ((ArrayList<String>) invocationContext.getContextData().computeIfAbsent("infoList", (key) -> new ArrayList<String>()));
        infoList.add("first stage ready ->");
        try {
            return invocationContext.proceed();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
