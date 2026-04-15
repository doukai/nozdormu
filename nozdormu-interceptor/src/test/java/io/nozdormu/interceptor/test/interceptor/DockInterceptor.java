package io.nozdormu.interceptor.test.interceptor;

import io.nozdormu.interceptor.test.annotation.Bindings;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

@ApplicationScoped
@Bindings.Dock
@Priority(0)
@Interceptor
public class DockInterceptor {

  @AroundInvoke
  public Object aroundInvoke(InvocationContext invocationContext) {
    try {
      return invocationContext.proceed() + " -> dock checked";
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
