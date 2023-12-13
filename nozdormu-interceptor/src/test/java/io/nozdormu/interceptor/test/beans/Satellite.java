package io.nozdormu.interceptor.test.beans;

import io.nozdormu.interceptor.test.annotation.Install;
import io.nozdormu.interceptor.test.annotation.Launch;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class Satellite {

    private List<String> infoList = new ArrayList<>();

    private final Owner owner;

    @Install
    public Satellite(Owner owner) {
        this.owner = owner;
    }

    @Launch
    public String startup(String name) {
        return "hello " + name + " I am " + owner.getName();
    }

    public void setInfoList(List<String> infoList) {
        this.infoList = infoList;
    }

    public String checkResult() {
        return String.join(" ", infoList) + " all check ready, fire";
    }
}
