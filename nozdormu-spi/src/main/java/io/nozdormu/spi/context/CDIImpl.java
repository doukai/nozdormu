package io.nozdormu.spi.context;

import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.inject.Named;
import reactor.util.annotation.NonNull;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Iterator;
import java.util.stream.Collectors;

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
                return BeanContext.getInstance(beanClass, Default.class.getCanonicalName());
            }
        } else if (annotations.length > 1) {
            return new InstanceImpl<>(
                    BeanContext.getProviderListWithMeta(beanClass).stream()
                            .filter(tuple2 ->
                                    Arrays.stream(annotations)
                                            .anyMatch(annotation -> {
                                                        if (annotation instanceof NamedLiteral) {
                                                            return tuple2.getT1().containsKey(Named.class.getName()) &&
                                                                    tuple2.getT1().get(Named.class.getName()).equals(((NamedLiteral) annotation).value());
                                                        } else if (annotation instanceof Default.Literal) {
                                                            return tuple2.getT1().containsKey(Default.class.getName()) &&
                                                                    tuple2.getT1().get(Default.class.getName()).equals(true);
                                                        }
                                                        return true;
                                                    }
                                            )
                            )
                            .collect(Collectors.toList())
            );
        }
        return new InstanceImpl<>(BeanContext.getProviderListWithMeta(beanClass));
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
        throw new UnsatisfiedResolutionException();
    }

    @Override
    @NonNull
    public Iterator<T> iterator() {
        throw new UnsatisfiedResolutionException();
    }
}
