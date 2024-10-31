package io.nozdormu.spi.decompiler;

import javax.lang.model.element.TypeElement;
import java.util.Optional;

public interface TypeElementDecompiler {

    boolean canLoad(TypeElement typeElement);

    String decompile(TypeElement typeElement);

    default Optional<String> decompileOrEmpty(TypeElement typeElement) {
        if (canLoad(typeElement)) {
            return Optional.of(decompile(typeElement));
        }
        return Optional.empty();
    }
}
