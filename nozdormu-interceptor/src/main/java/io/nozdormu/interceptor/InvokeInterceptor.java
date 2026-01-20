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

public interface InvokeInterceptor {

    InvocationContext getContext();

    default InvocationContextProxy getContextProxy() {
        return (InvocationContextProxy) getContext();
    }

    Object aroundInvoke(InvocationContext invocationContext);

    static List<InvokeInterceptor> getInvokeInterceptorList(String... annotationNames) {
        return Lists
                .reverse(
                        BeanContext.getImplSupplierMap(InvokeInterceptor.class).values().stream()
                                .filter(beanSupplier -> beanSupplier.getQualifiers().containsKey(Named.class.getName()))
                                .filter(beanSupplier ->
                                        Stream.of(annotationNames)
                                                .anyMatch(annotationName -> beanSupplier.getQualifiers().get(Named.class.getName()).get("value").equals(annotationName))
                                )
                                .sorted(Comparator.comparing(BeanSupplier::getPriority, Comparator.nullsLast(Integer::compareTo)))
                                .map(beanSupplier -> (InvokeInterceptor) beanSupplier.getSupplier().get())
                                .collect(Collectors.toList())
                );
    }
}
