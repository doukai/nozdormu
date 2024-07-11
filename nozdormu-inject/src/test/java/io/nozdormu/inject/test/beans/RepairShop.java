package io.nozdormu.inject.test.beans;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class RepairShop {

    private final IEngine defaultEngine;

    private final IEngine v12Engine;

    private final List<IEngine> engineList;

    @Inject
    public RepairShop(@Default IEngine defaultEngine, @Named("v12") IEngine v12Engine, Instance<IEngine> engineInstance) {
        this.defaultEngine = defaultEngine;
        this.v12Engine = v12Engine;
        this.engineList = engineInstance.stream().collect(Collectors.toList());
    }

    public IEngine getDefaultEngine() {
        return defaultEngine;
    }

    public IEngine getV12Engine() {
        return v12Engine;
    }

    public List<IEngine> getEngineList() {
        return engineList;
    }
}
