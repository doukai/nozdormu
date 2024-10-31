package io.nozdormu.decompiler;

import io.nozdormu.spi.decompiler.TypeElementDecompiler;
import org.jetbrains.java.decompiler.api.Decompiler;

import javax.lang.model.element.TypeElement;

public class VineflowerDecompiler  implements TypeElementDecompiler {



    @Override
    public boolean canLoad(TypeElement typeElement) {
        return false;
    }

    @Override
    public String decompile(TypeElement typeElement) {
        Decompiler decompiler = Decompiler.builder().build();
        // JAR 文件路径
        String jarFilePath = "path/to/your/file.jar";

        return "";
    }
}
