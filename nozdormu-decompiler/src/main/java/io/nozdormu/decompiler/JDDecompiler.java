package io.nozdormu.decompiler;

import io.nozdormu.spi.decompiler.TypeElementDecompiler;
import org.benf.cfr.reader.CfrDriverImpl;
import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;
import org.benf.cfr.reader.entities.ClassFile;
import org.benf.cfr.reader.util.AnalysisType;
import org.benf.cfr.reader.util.getopt.OptionsImpl;
import org.jd.core.v1.ClassFileToJavaSourceDecompiler;

import javax.lang.model.element.TypeElement;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public class JDDecompiler implements TypeElementDecompiler {

    private final ClassLoader classLoader;

    public JDDecompiler(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public boolean canLoad(TypeElement typeElement) {
        return true;
    }

    @Override
    public String decompile(TypeElement typeElement) {
        StringBuilder javaSource = new StringBuilder();

        StringBuilder summaryOutput = new StringBuilder();
        StringBuilder exceptionsOutput = new StringBuilder();
//        String classFilePath = Objects.requireNonNull(classLoader.getResource(getDecompilerClassName(typeElement.getQualifiedName().toString()).replace(".", "/") + ".class")).getPath();
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
        try {
            URL url = classLoader.getResource(getDecompilerClassName(typeElement.getQualifiedName().toString()).replace(".", "/") + ".class");
            Path path;
            try {
                path = Path.of(url.toURI());
            } catch (FileSystemNotFoundException fileSystemNotFoundException) {
                Map<String, String> env = new HashMap<>();
                try {
                    FileSystem fileSystem = FileSystems.newFileSystem(url.toURI(), env);
                    path = fileSystem.getPath(url.getPath());
                } catch (URISyntaxException e) {
                    throw new RuntimeException(e);
                }
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
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
                            return castSink(javaSource::append);
                        case EXCEPTION:
                            switch (sinkClass) {
                                case EXCEPTION_MESSAGE:
                                    return castSink(exceptionSink);
                                // Always have to support STRING
                                case STRING:
                                    return castSink(summaryOutput::append);
                                default:
                                    throw new IllegalArgumentException("Sink factory does not support " + sinkClass);
                            }
                        default:
                            return ignored -> {
                            };
                    }
                }
            };

            Map<String, String> options = new HashMap<>();  // 设置 CFR 的反编译选项
            options.put(OptionsImpl.ANALYSE_AS.getName(), String.valueOf(AnalysisType.CLASS));
            CfrDriver cfrDriver = new CfrDriver.Builder()
                    .withOptions(options)
                    .withOutputSink(sinkFactory)
                    .build();
            cfrDriver.analyse(List.of(url.toString()));

            return javaSource.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }


    }

    @Override
    public Optional<String> decompileOrEmpty(TypeElement typeElement) {
        if (canLoad(typeElement)) {
            return Optional.of(decompile(typeElement));
        }
        return Optional.empty();
    }

    public String getDecompilerClassName(String className) {
        try {
            return Class.forName(className, false, classLoader).getName();
        } catch (ClassNotFoundException e) {
            int i = className.lastIndexOf(".");
            String nestedClassName = className.substring(0, i) + "$" + className.substring(i + 1);
            return getDecompilerClassName(nestedClassName);
        }
    }
}
