package io.nozdormu.spi.context;

import com.google.common.collect.Iterators;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.inject.Provider;
import reactor.util.annotation.NonNull;

import java.lang.annotation.Annotation;
import java.util.Iterator;

public class SingletonInstanceImpl<T> implements Instance<T> {

    private final Provider<T> provider;

    public SingletonInstanceImpl(Provider<T> provider) {
        this.provider = provider;
    }

    @Override
    public Instance<T> select(Annotation... qualifiers) {
        return null;
    }

    @Override
    public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
        return null;
    }

    @Override
    public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
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
    public void destroy(T instance) {

    }

    @Override
    public T get() {
        if (provider != null) {
            return provider.get();
        }
        return null;
    }

    @Override
    @NonNull
    public Iterator<T> iterator() {
        return Iterators.singletonIterator(provider.get());
    }
}
