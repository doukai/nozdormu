package io.nozdormu.spi.context;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
import java.util.Iterator;

public class CDIImpl<T> extends CDI<T> {
    @Override
    public BeanManager getBeanManager() {
        return null;
    }

    @Override
    public Instance<T> select(Annotation... annotations) {
        return null;
    }

    @Override
    public <U extends T> Instance<U> select(Class<U> beanClass, Annotation... annotations) {
        if (annotations.length > 0 && annotations[0] instanceof NamedLiteral) {
            return BeanContext.getInstance(beanClass, ((NamedLiteral) annotations[0]).value());
        }
        return new InstanceImpl<>(BeanContext.getPriorityProviderList(beanClass));
    }

    @Override
    public <U extends T> Instance<U> select(TypeLiteral<U> typeLiteral, Annotation... annotations) {
        return null;
    }

    @Override
    public boolean isUnsatisfied() {
        return false;
    }

    @Override
    public boolean isAmbiguous() {
        return false;
    }

    @Override
    public void destroy(T t) {

    }

    @Override
    public T get() {
        return null;
    }

    @Override
    public Iterator<T> iterator() {
        return null;
    }
}
