package io.nozdormu.spi.decompiler;

import javax.lang.model.element.TypeElement;

public interface TypeElementDecompiler {

    String decompile(TypeElement typeElement);
}
