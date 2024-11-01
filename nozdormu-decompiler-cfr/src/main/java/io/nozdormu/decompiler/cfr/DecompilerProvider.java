package io.nozdormu.decompiler.cfr;

import com.google.auto.service.AutoService;
import io.nozdormu.spi.decompiler.TypeElementDecompiler;
import io.nozdormu.spi.decompiler.TypeElementDecompilerProvider;

@AutoService(TypeElementDecompilerProvider.class)
public class DecompilerProvider implements TypeElementDecompilerProvider {
    @Override
    public TypeElementDecompiler create(ClassLoader classLoader) {
        return new CFRDecompiler(classLoader);
    }
}
