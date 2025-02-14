package io.nozdormu.spi.async;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Async {
    String defaultIfEmpty() default "";
}
