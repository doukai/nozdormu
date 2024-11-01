package io.nozdormu.decompiler;

import com.strobel.assembler.metadata.JarTypeLoader;
import com.strobel.decompiler.Decompiler;
import com.strobel.decompiler.DecompilerSettings;
import io.nozdormu.spi.decompiler.TypeElementDecompiler;

import javax.lang.model.element.TypeElement;

public class ProcyonDecompiler  implements TypeElementDecompiler {

    final DecompilerSettings settings = DecompilerSettings.javaDefaults();

    @Override
    public boolean canLoad(TypeElement typeElement) {
        return false;
    }

    @Override
    public String decompile(TypeElement typeElement) {
//        JarTypeLoader
//        settings.setTypeLoader();
//        Decompiler.decompile(
//                "java/lang/String",
//                new PlainTextOutput(writer),
//                settings
//        );
        return "";
    }
}
