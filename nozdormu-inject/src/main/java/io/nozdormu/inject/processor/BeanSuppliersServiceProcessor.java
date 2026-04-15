package io.nozdormu.inject.processor;

import com.google.auto.service.AutoService;
import io.nozdormu.spi.context.BeanSuppliers;
import io.nozdormu.spi.context.GeneratedBeanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@SupportedAnnotationTypes({"io.nozdormu.spi.context.GeneratedBeanSupplier"})
@AutoService(Processor.class)
public class BeanSuppliersServiceProcessor extends AbstractProcessor {

  private static final Logger logger = LoggerFactory.getLogger(BeanSuppliersServiceProcessor.class);

  private static final String SERVICE_FILE = "META-INF/services/" + BeanSuppliers.class.getName();

  private final Map<String, TypeElement> beanSupplierTypeElementMap = new LinkedHashMap<>();

  private Filer filer;

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    this.filer = processingEnv.getFiler();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    roundEnv.getElementsAnnotatedWith(GeneratedBeanSupplier.class).stream()
        .filter(element -> element.getKind().isClass())
        .map(TypeElement.class::cast)
        .forEach(
            typeElement ->
                beanSupplierTypeElementMap.put(
                    typeElement.getQualifiedName().toString(), typeElement));

    if (!roundEnv.processingOver() || beanSupplierTypeElementMap.isEmpty()) {
      return false;
    }

    writeServiceFile();
    return false;
  }

  private void writeServiceFile() {
    try {
      Writer writer =
          filer
              .createResource(
                  StandardLocation.CLASS_OUTPUT,
                  "",
                  SERVICE_FILE,
                  beanSupplierTypeElementMap.values().toArray(Element[]::new))
              .openWriter();
      writer.write(
          beanSupplierTypeElementMap.keySet().stream()
              .sorted()
              .collect(Collectors.joining(System.lineSeparator(), "", System.lineSeparator())));
      writer.close();
      logger.info("{} build success", SERVICE_FILE);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
