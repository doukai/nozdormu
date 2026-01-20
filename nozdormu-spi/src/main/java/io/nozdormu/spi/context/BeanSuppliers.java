package io.nozdormu.spi.context;

import java.util.Map;

public interface BeanSuppliers {

    Map<String, Map<String, BeanSupplier>> getBeanSuppliers();
}
