package io.nozdormu.interceptor.test.interceptor;

import io.nozdormu.interceptor.test.annotation.Install;
import io.nozdormu.interceptor.test.beans.Satellite;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.interceptor.AroundConstruct;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
@Install
@Priority(1)
@Interceptor
public class SecondInstallInterceptor {

    @SuppressWarnings("unchecked")
    @AroundConstruct
    public Object aroundConstruct(InvocationContext invocationContext) {
        List<String> infoList = ((ArrayList<String>) invocationContext.getContextData().computeIfAbsent("infoList", (key) -> new ArrayList<String>()));
        infoList.add("second stage ready ->");
        try {
            Satellite satellite = (Satellite) invocationContext.proceed();
            satellite.setInfoList(infoList);
            return satellite;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
