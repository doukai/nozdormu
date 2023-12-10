package io.nozdormu.inject;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.Context;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.resolution.types.ResolvedTypeVariable;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.sun.source.util.Trees;
import io.nozdormu.inject.error.InjectionProcessErrorType;
import io.nozdormu.inject.error.InjectionProcessException;
import org.tinylog.Logger;

import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.nozdormu.inject.error.InjectionProcessErrorType.CANNOT_PARSER_SOURCE_CODE;
import static io.nozdormu.inject.error.InjectionProcessErrorType.PUBLIC_CLASS_NOT_EXIST;
import static javax.lang.model.element.ElementKind.CLASS;

public class ProcessorManager {

    private final ProcessingEnvironment processingEnv;
    private RoundEnvironment roundEnv;
    private final Trees trees;
    private final Filer filer;
    private final Elements elements;
    private final JavaParser javaParser;
    private final JavaSymbolSolver javaSymbolSolver;
    private final CombinedTypeSolver combinedTypeSolver;

    public ProcessorManager(ProcessingEnvironment processingEnv, ClassLoader classLoader) {
        this.processingEnv = processingEnv;
        this.filer = processingEnv.getFiler();
        this.elements = processingEnv.getElementUtils();
        this.trees = Trees.instance(processingEnv);
        this.combinedTypeSolver = new CombinedTypeSolver();
        Path generatedSourcePath = getGeneratedSourcePath();
        JavaParserTypeSolver javaParserTypeSolver = new JavaParserTypeSolver(getSourcePath(generatedSourcePath));
        JavaParserTypeSolver generatedJavaParserTypeSolver = new JavaParserTypeSolver(generatedSourcePath);
        ClassLoaderTypeSolver classLoaderTypeSolver = new ClassLoaderTypeSolver(classLoader);
        ReflectionTypeSolver reflectionTypeSolver = new ReflectionTypeSolver();
        combinedTypeSolver.add(javaParserTypeSolver);
        combinedTypeSolver.add(generatedJavaParserTypeSolver);
        combinedTypeSolver.add(classLoaderTypeSolver);
        combinedTypeSolver.add(reflectionTypeSolver);
        this.javaSymbolSolver = new JavaSymbolSolver(combinedTypeSolver);
        this.javaParser = new JavaParser();
        this.javaParser.getParserConfiguration().setSymbolResolver(javaSymbolSolver);
    }

    public void setRoundEnv(RoundEnvironment roundEnv) {
        this.roundEnv = roundEnv;
    }

    private Path getGeneratedSourcePath() {
        try {
            FileObject tmp = filer.createResource(StandardLocation.SOURCE_OUTPUT, "", UUID.randomUUID().toString());
            Writer writer = tmp.openWriter();
            writer.write("");
            writer.close();
            Path path = Paths.get(tmp.toUri());
            Files.deleteIfExists(path);
            Path generatedSourcePath = path.getParent();
            Logger.info("generated source path: {}", generatedSourcePath.toString());
            return generatedSourcePath;
        } catch (IOException e) {
            Logger.error(e);
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "unable to determine generated source path.");
            throw new RuntimeException(e);
        }
    }

    private Path getSourcePath(Path generatedSourcePath) {
        Path sourcePath = generatedSourcePath.getParent().getParent().getParent().getParent().getParent().getParent().resolve("src/main/java");
        Logger.info("source path: {}", sourcePath.toString());
        return sourcePath;
    }

    public String getRootPackageName() {
        return roundEnv.getRootElements().stream()
                .filter(element -> element.getKind().equals(ElementKind.PACKAGE))
                .map(element -> (PackageElement) element)
                .reduce((left, right) -> left.getQualifiedName().toString().length() < right.getQualifiedName().length() ? left : right)
                .map(packageElement -> packageElement.getQualifiedName().toString())
                .orElseGet(() ->
                        roundEnv.getRootElements().stream()
                                .filter(element -> element.getKind().equals(ElementKind.ENUM) ||
                                        element.getKind().equals(CLASS) ||
                                        element.getKind().equals(ElementKind.INTERFACE) ||
                                        element.getKind().equals(ElementKind.ANNOTATION_TYPE))
                                .map(elements::getPackageOf)
                                .reduce((left, right) -> left.getQualifiedName().toString().length() < right.getQualifiedName().length() ? left : right)
                                .map(packageElement -> packageElement.getQualifiedName().toString())
                                .orElseThrow(() -> new InjectionProcessException(InjectionProcessErrorType.ROOT_PACKAGE_NOT_EXIST))
                );
    }

    public void writeToFiler(CompilationUnit compilationUnit) {
        getPublicClassOrInterfaceDeclaration(compilationUnit)
                .ifPresent(classOrInterfaceDeclaration -> {
                            try {
                                String qualifiedName = getQualifiedName(classOrInterfaceDeclaration);
                                Writer writer = filer.createSourceFile(qualifiedName).openWriter();
                                writer.write(compilationUnit.toString());
                                writer.close();
                                Logger.info("{} build success", qualifiedName);
                            } catch (IOException e) {
                                Logger.error(e);
                                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "java file create failed");
                                throw new RuntimeException(e);
                            }
                        }
                );
    }

    public void createResource(String name, String content) {
        try {
            Writer writer = filer.createResource(StandardLocation.CLASS_OUTPUT, "", name).openWriter();
            writer.write(content);
            writer.close();
            Logger.info("{} build success", name);
        } catch (IOException e) {
            Logger.error(e);
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "resource file create failed");
            throw new RuntimeException(e);
        }
    }

    public Optional<FileObject> getResource(String fileName) {
        try {
            FileObject resource = processingEnv.getFiler().getResource(StandardLocation.SOURCE_PATH, "", fileName);
            return Optional.ofNullable(resource);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<CompilationUnit> parse(TypeElement typeElement) {
        return parse(trees.getPath(typeElement).getCompilationUnit().toString());
    }

    private Optional<CompilationUnit> parse(String sourceCode) {
        return javaParser.parse(sourceCode).getResult();
    }

    public List<CompilationUnit> getCompilationUnitListWithAnnotationClass(Class<? extends Annotation> annotationClass) {
        return roundEnv.getElementsAnnotatedWith(annotationClass).stream()
                .filter(element -> element.getKind().equals(CLASS))
                .map(element -> parse((TypeElement) element).orElseThrow(() -> new InjectionProcessException(CANNOT_PARSER_SOURCE_CODE.bind(((TypeElement) element).getQualifiedName()))))
                .collect(Collectors.toList());
    }

    public Optional<ClassOrInterfaceDeclaration> getPublicClassOrInterfaceDeclaration(CompilationUnit compilationUnit) {
        return compilationUnit.getTypes().stream()
                .filter(typeDeclaration -> typeDeclaration.hasModifier(Modifier.Keyword.PUBLIC))
                .filter(BodyDeclaration::isClassOrInterfaceDeclaration)
                .map(BodyDeclaration::asClassOrInterfaceDeclaration)
                .findFirst();
    }

    public ClassOrInterfaceDeclaration getPublicClassOrInterfaceDeclarationOrError(CompilationUnit compilationUnit) {
        return getPublicClassOrInterfaceDeclaration(compilationUnit)
                .orElseThrow(() -> new InjectionProcessException(PUBLIC_CLASS_NOT_EXIST.bind(compilationUnit.toString())));
    }

    public String getQualifiedName(ClassOrInterfaceDeclaration classOrInterfaceDeclaration) {
        ResolvedReferenceTypeDeclaration resolvedReferenceTypeDeclaration = javaSymbolSolver.resolveDeclaration(classOrInterfaceDeclaration, ResolvedReferenceTypeDeclaration.class);
        return resolvedReferenceTypeDeclaration.getQualifiedName();
    }

    public String getQualifiedName(Type type) {
        if (type.isClassOrInterfaceType()) {
            return getQualifiedName(type.asClassOrInterfaceType());
        } else if (type.isPrimitiveType()) {
            return getQualifiedName(type.asPrimitiveType().toBoxedType());
        } else if (type.isArrayType()) {
            return getQualifiedName(type.asArrayType().getElementType()) + "[]";
        }
        return type.asString();
    }

    public String getQualifiedName(ClassOrInterfaceType type) {
        ResolvedReferenceType resolvedReferenceType = getResolvedType(type).asReferenceType();
        return resolvedReferenceType.getQualifiedName();
    }

    public ResolvedType getResolvedType(Type type) {
        try {
            return javaSymbolSolver.toResolvedType(type, ResolvedReferenceType.class);
        } catch (UnsolvedSymbolException e) {
            if (type.isClassOrInterfaceType()) {
                return getResolvedInnerType(type.asClassOrInterfaceType());
            }
            throw e;
        }
    }

    public ResolvedType getResolvedInnerType(ClassOrInterfaceType classOrInterfaceType) {
        if (classOrInterfaceType.getScope().isPresent()) {
            Context context = JavaParserFactory.getContext(classOrInterfaceType, combinedTypeSolver);
            String name = classOrInterfaceType.getNameAsString();
            SymbolReference<ResolvedTypeDeclaration> ref = context.solveType(
                    name,
                    classOrInterfaceType.getTypeArguments()
                            .map(types ->
                                    types.stream()
                                            .map(this::getResolvedType)
                                            .collect(Collectors.toList())
                            )
                            .orElse(null)
            );
            if (!ref.isSolved()) {
                throw new UnsolvedSymbolException(name);
            }
            ResolvedTypeDeclaration typeDeclaration = ref.getCorrespondingDeclaration();
            List<ResolvedType> typeParameters = Collections.emptyList();
            if (classOrInterfaceType.getTypeArguments().isPresent()) {
                typeParameters = classOrInterfaceType.getTypeArguments().get().stream().map(this::getResolvedType).collect(Collectors.toList());
            }
            if (typeDeclaration.isTypeParameter()) {
                return new ResolvedTypeVariable(typeDeclaration.asTypeParameter()).asReferenceType();
            }
            return new ReferenceTypeImpl((ResolvedReferenceTypeDeclaration) typeDeclaration, typeParameters);
        }
        throw new RuntimeException("scope not exist:" + classOrInterfaceType.getNameAsString());
    }

    public Optional<Expression> findAnnotationValue(AnnotationExpr annotationExpr) {
        if (annotationExpr.isSingleMemberAnnotationExpr()) {
            return Optional.of(annotationExpr.asSingleMemberAnnotationExpr().getMemberValue());
        } else if (annotationExpr.isNormalAnnotationExpr()) {
            return annotationExpr.asNormalAnnotationExpr().getPairs().stream()
                    .filter(memberValuePair -> memberValuePair.getNameAsString().equals("value"))
                    .findFirst()
                    .map(MemberValuePair::getValue);
        }
        return Optional.empty();
    }
}
