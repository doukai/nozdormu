package io.nozdormu.decompiler.cfr;

import com.google.common.base.Strings;
import io.nozdormu.spi.decompiler.TypeElementDecompiler;
import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;

import javax.lang.model.element.TypeElement;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.*;
import java.util.stream.Collectors;

import static io.nozdormu.spi.utils.DecompileUtil.getDecompileClassName;

public class CFRDecompiler implements TypeElementDecompiler {

    private final ClassLoader classLoader;

    private static final Map<String, String> DECOMPILED_CACHE = new HashMap<>();

    public CFRDecompiler(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public boolean canLoad(TypeElement typeElement) {
        String decompileClassName = getDecompileClassName(typeElement.getQualifiedName().toString(), classLoader);
        try {
            Class.forName(decompileClassName, false, classLoader);
            return DECOMPILED_CACHE.containsKey(decompileClassName) || decompileAndCache(decompileClassName);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public String decompile(TypeElement typeElement) {
        String decompileClassName = getDecompileClassName(typeElement.getQualifiedName().toString(), classLoader);
        if (DECOMPILED_CACHE.containsKey(decompileClassName) || decompileAndCache(decompileClassName)) {
            return DECOMPILED_CACHE.get(decompileClassName);
        }
        throw new RuntimeException(decompileClassName + " not find");
    }

    private boolean decompileAndCache(String decompileClassName) {
        StringWriter summaryOutput = new StringWriter();
        OutputSinkFactory.Sink<String> summarySink = summaryOutput::append;
        StringWriter exceptionsOutput = new StringWriter();

        OutputSinkFactory.Sink<SinkReturns.ExceptionMessage> exceptionSink = exceptionMessage -> {
            exceptionsOutput.append(exceptionMessage.getPath()).append('\n');
            exceptionsOutput.append(exceptionMessage.getMessage()).append('\n');

            Exception exception = exceptionMessage.getThrownException();
            exceptionsOutput
                    .append(exception.getClass().getName())
                    .append(": ")
                    .append(exception.getMessage())
                    .append("\n\n");
        };

        List<SinkReturns.DecompiledMultiVer> decompiledList = new ArrayList<>();
        OutputSinkFactory.Sink<SinkReturns.DecompiledMultiVer> decompiledSourceSink = decompiledList::add;

        OutputSinkFactory sinkFactory = new OutputSinkFactory() {
            @Override
            public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> collection) {
                switch (sinkType) {
                    case JAVA:
                        return Collections.singletonList(SinkClass.DECOMPILED_MULTIVER);
                    case EXCEPTION:
                        return Collections.singletonList(SinkClass.EXCEPTION_MESSAGE);
                    case SUMMARY:
                        return Collections.singletonList(SinkClass.STRING);
                    default:
                        // Required to always support STRING
                        return Collections.singletonList(SinkClass.STRING);
                }
            }

            @SuppressWarnings("unchecked")
            private <T> Sink<T> castSink(Sink<?> sink) {
                return (Sink<T>) sink;
            }

            @Override
            public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
                switch (sinkType) {
                    case JAVA:
                        if (sinkClass != SinkClass.DECOMPILED_MULTIVER) {
                            throw new IllegalArgumentException("Sink class " + sinkClass + " is not supported for decompiled output");
                        }
                        return castSink(decompiledSourceSink);
                    case EXCEPTION:
                        switch (sinkClass) {
                            case EXCEPTION_MESSAGE:
                                return castSink(exceptionSink);
                            // Always have to support STRING
                            case STRING:
                                return castSink(summarySink);
                            default:
                                throw new IllegalArgumentException("Sink factory does not support " + sinkClass);
                        }
                    case SUMMARY:
                        return castSink(summarySink);
                    default:
                        return ignored -> {
                        };
                }
            }
        };

        Map<String, String> options = new HashMap<>();  // 设置 CFR 的反编译选项
        CfrDriver cfrDriver = new CfrDriver.Builder()
                .withOptions(options)
                .withOutputSink(sinkFactory)
                .build();

        try {
            Class<?> decompileClass = Class.forName(decompileClassName, false, classLoader);
            CodeSource codeSource = decompileClass.getProtectionDomain().getCodeSource();
            if (codeSource == null) {
                return false;
            }
            Path path = Paths.get(codeSource.getLocation().toURI());
            cfrDriver.analyse(List.of(path.toAbsolutePath().toString()));
            if (!Strings.isNullOrEmpty(exceptionsOutput.toString())) {
                throw new RuntimeException(exceptionsOutput.toString());
            }
            DECOMPILED_CACHE.putAll(
                    decompiledList.stream()
                            .map(decompiledMultiVer ->
                                    new AbstractMap.SimpleEntry<>(
                                            decompiledMultiVer.getPackageName() + "." + decompiledMultiVer.getClassName(),
                                            decompiledMultiVer.getJava()

                                    )
                            )
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
            );
            return DECOMPILED_CACHE.containsKey(decompileClassName);
        } catch (ClassNotFoundException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
