package io.nozdormu.spi.context;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.inject.Provider;

import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.List;

public class InstanceImpl<T> implements Instance<T> {

    private final List<Provider<T>> providerList;

    public InstanceImpl(List<Provider<T>> providerList) {
        this.providerList = providerList;
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
        if (providerList != null && !providerList.isEmpty()) {
            return providerList.get(0).get();
        }
        return null;
    }

    @Override
    public Iterator<T> iterator() {
        return providerList.stream().map(Provider::get).iterator();
    }
}
