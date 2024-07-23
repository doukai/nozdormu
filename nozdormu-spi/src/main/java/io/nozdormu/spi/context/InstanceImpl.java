package io.nozdormu.spi.context;

import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import reactor.util.annotation.NonNull;
import reactor.util.function.Tuple2;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class InstanceImpl<T> implements Instance<T> {

    private final List<Tuple2<Map<String, Object>, Provider<T>>> providerListWithMeta;

    public InstanceImpl(List<Tuple2<Map<String, Object>, Provider<T>>> providerListWithMeta) {
        this.providerListWithMeta = providerListWithMeta;
    }

    @Override
    public Instance<T> select(Annotation... qualifiers) {
        if (qualifiers.length > 0) {
            return new InstanceImpl<>(
                    providerListWithMeta.stream()
                            .filter(tuple2 ->
                                    Arrays.stream(qualifiers)
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
        return this;
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
        if (providerListWithMeta != null && !providerListWithMeta.isEmpty()) {
            return providerListWithMeta.get(0).getT2().get();
        }
        return null;
    }

    @Override
    @NonNull
    public Iterator<T> iterator() {
        return providerListWithMeta.stream().map(Tuple2::getT2).map(Provider::get).iterator();
    }
}
