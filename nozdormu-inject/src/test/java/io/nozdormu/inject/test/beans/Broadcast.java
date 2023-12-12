package io.nozdormu.inject.test.beans;

import jakarta.enterprise.context.SessionScoped;

@SessionScoped
public class Broadcast {

    public String getName(){
        return "BBC";
    }
}
