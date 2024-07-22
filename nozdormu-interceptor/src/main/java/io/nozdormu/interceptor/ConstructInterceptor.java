package io.nozdormu.interceptor;

import com.google.common.collect.Lists;
import io.nozdormu.spi.context.BeanContext;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import jakarta.interceptor.InvocationContext;
import reactor.util.function.Tuple2;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface ConstructInterceptor {

    InvocationContext getContext();

    default InvocationContextProxy getContextProxy() {
        return (InvocationContextProxy) getContext();
    }

    Object aroundConstruct(InvocationContext invocationContext);

    static List<ConstructInterceptor> getConstructInterceptorList(String... annotationNames) {
        return Lists
                .reverse(
                        BeanContext.getProviderListWithMeta(ConstructInterceptor.class).stream()
                                .filter(tuple2 -> tuple2.getT1().containsKey(Named.class.getName()))
                                .filter(tuple2 ->
                                        Stream.of(annotationNames)
                                                .anyMatch(annotationName -> tuple2.getT1().get(Named.class.getName()).equals(annotationName))
                                )
                                .map(Tuple2::getT2)
                                .map(Provider::get)
                                .collect(Collectors.toList())
                );
    }
}
