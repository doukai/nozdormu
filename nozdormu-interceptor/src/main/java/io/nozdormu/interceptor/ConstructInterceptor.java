package io.nozdormu.interceptor;

import com.google.common.collect.Lists;
import io.nozdormu.spi.context.BeanContext;
import io.nozdormu.spi.context.BeanSupplier;
import jakarta.inject.Named;
import jakarta.interceptor.InvocationContext;

import java.util.Comparator;
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
                        BeanContext.getImplSupplierMap(ConstructInterceptor.class).values().stream()
                                .filter(beanSupplier -> beanSupplier.getQualifiers().containsKey(Named.class.getName()))
                                .filter(beanSupplier ->
                                        Stream.of(annotationNames)
                                                .anyMatch(annotationName -> beanSupplier.getQualifiers().get(Named.class.getName()).get("value").equals(annotationName))
                                )
                                .sorted(Comparator.comparing(BeanSupplier::getPriority, Comparator.nullsLast(Integer::compareTo)))
                                .map(beanSupplier -> (ConstructInterceptor) beanSupplier.getSupplier().get())
                                .collect(Collectors.toList())
                );
    }
}
