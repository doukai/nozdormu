package io.nozdormu.inject.test.beans;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;

@ApplicationScoped
@Default
@Priority(1)
public class Engine implements IEngine {

  public String getName() {
    return "V8 Engine";
  }
}
