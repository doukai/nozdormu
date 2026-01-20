package io.nozdormu.spi.context;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.TypeLiteral;
import reactor.util.annotation.NonNull;

import java.lang.annotation.Annotation;
import java.util.Iterator;

import static io.nozdormu.spi.utils.QualifierUtil.toQualifierMap;

public class CDIImpl<T> extends CDI<T> {
    @Override
    public BeanManager getBeanManager() {
        return null;
    }

    @Override
    public Instance<T> select(Annotation... annotations) {
        return BeanContext.getInstance(toQualifierMap(annotations));
    }

    @Override
    public <U extends T> Instance<U> select(Class<U> beanClass, Annotation... annotations) {
        return BeanContext.getInstance(beanClass, toQualifierMap(annotations));
    }

    @Override
    public <U extends T> Instance<U> select(TypeLiteral<U> typeLiteral, Annotation... annotations) {
        return select(typeLiteral.getRawType(), annotations);
    }

    @Override
    public boolean isUnsatisfied() {
        return BeanContext.getBeanImplSupplierMap().isEmpty();
    }

    @Override
    public boolean isAmbiguous() {
        return BeanContext.getBeanImplSupplierMap().size() > 1;
    }

    @Override
    public void destroy(T t) {
        // No-op: no lifecycle hooks tracked for instances.
    }

    @Override
    public T get() {
        throw new UnsatisfiedResolutionException();
    }

    @Override
    @NonNull
    public Iterator<T> iterator() {
        throw new UnsatisfiedResolutionException();
    }

}
