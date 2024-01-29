package io.nozdormu.spi.decompiler;

import javax.lang.model.element.TypeElement;
import java.util.Optional;

public interface TypeElementDecompiler {

    boolean canLoad(TypeElement typeElement);

    String decompile(TypeElement typeElement);

    Optional<String> decompileOrEmpty(TypeElement typeElement);
}
