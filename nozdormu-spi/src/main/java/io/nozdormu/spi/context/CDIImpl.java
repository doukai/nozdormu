package io.nozdormu.spi.context;

import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
import java.util.Arrays;
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
        if (annotations.length == 1) {
            if (annotations[0] instanceof NamedLiteral) {
                return BeanContext.getInstance(beanClass, ((NamedLiteral) annotations[0]).value());
            } else if (annotations[0] instanceof Default.Literal) {
                return BeanContext.getDefaultInstance(beanClass);
            }
        } else if (annotations.length > 1) {
            return new InstanceImpl<>(
                    BeanContext.getNameProviderList(
                            beanClass,
                            Arrays.stream(annotations)
                                    .filter(annotation -> annotation instanceof NamedLiteral)
                                    .map(annotation -> ((NamedLiteral) annotation).value())
                                    .toArray(String[]::new)
                    )
            );
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
