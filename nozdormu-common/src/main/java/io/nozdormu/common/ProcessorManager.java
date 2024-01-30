package io.nozdormu.common;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.Context;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.*;
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
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import io.nozdormu.spi.decompiler.TypeElementDecompiler;
import io.nozdormu.spi.decompiler.TypeElementDecompilerProvider;
import io.nozdormu.spi.error.InjectionProcessErrorType;
import io.nozdormu.spi.error.InjectionProcessException;
import jakarta.enterprise.context.NormalScope;
import jakarta.inject.Inject;
import org.tinylog.Logger;
import reactor.core.publisher.Mono;

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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    private final TypeElementDecompiler typeElementDecompiler;
    private final ClassLoader classLoader;

    public ProcessorManager(ProcessingEnvironment processingEnv, ClassLoader classLoader) {
        this.processingEnv = processingEnv;
        this.filer = processingEnv.getFiler();
        this.elements = processingEnv.getElementUtils();
        this.trees = Trees.instance(processingEnv);
        this.combinedTypeSolver = new CombinedTypeSolver();
        Path generatedSourcePath = getGeneratedSourcePath();
        Path sourcePath = getSourcePath(generatedSourcePath);
        if (Files.exists(sourcePath)) {
            JavaParserTypeSolver javaParserTypeSolver = new JavaParserTypeSolver(sourcePath);
            combinedTypeSolver.add(javaParserTypeSolver);
        }
        Path testSourcePath = getTestSourcePath(generatedSourcePath);
        if (Files.exists(testSourcePath)) {
            JavaParserTypeSolver testJavaParserTypeSolver = new JavaParserTypeSolver(testSourcePath);
            combinedTypeSolver.add(testJavaParserTypeSolver);
        }
        JavaParserTypeSolver generatedJavaParserTypeSolver = new JavaParserTypeSolver(generatedSourcePath);
        ClassLoaderTypeSolver classLoaderTypeSolver = new ClassLoaderTypeSolver(classLoader);
        ReflectionTypeSolver reflectionTypeSolver = new ReflectionTypeSolver();
        combinedTypeSolver.add(generatedJavaParserTypeSolver);
        combinedTypeSolver.add(classLoaderTypeSolver);
        combinedTypeSolver.add(reflectionTypeSolver);
        this.javaSymbolSolver = new JavaSymbolSolver(combinedTypeSolver);
        this.javaParser = new JavaParser();
        this.javaParser.getParserConfiguration().setSymbolResolver(javaSymbolSolver);
        this.typeElementDecompiler = TypeElementDecompilerProvider.load(classLoader);
        this.classLoader = classLoader;
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

    private Path getTestSourcePath(Path generatedSourcePath) {
        Path sourcePath = generatedSourcePath.getParent().getParent().getParent().getParent().getParent().getParent().resolve("src/test/java");
        Logger.info("test source path: {}", sourcePath.toString());
        return sourcePath;
    }

    public String getRootPackageName() {
        return roundEnv.getRootElements().stream()
                .filter(element -> element.getKind().equals(ElementKind.PACKAGE))
                .map(element -> (PackageElement) element)
                .reduce((left, right) -> left.getQualifiedName().toString().split("\\.").length < right.getQualifiedName().toString().split("\\.").length ? left : right)
                .map(packageElement -> packageElement.getQualifiedName().toString())
                .orElseGet(() ->
                        roundEnv.getRootElements().stream()
                                .filter(element ->
                                        element.getKind().equals(ElementKind.ENUM) ||
                                                element.getKind().equals(CLASS) ||
                                                element.getKind().equals(ElementKind.INTERFACE) ||
                                                element.getKind().equals(ElementKind.ANNOTATION_TYPE)
                                )
                                .map(elements::getPackageOf)
                                .reduce((left, right) -> left.getQualifiedName().toString().split("\\.").length < right.getQualifiedName().toString().split("\\.").length ? left : right)
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
        } catch (FileNotFoundException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<CompilationUnit> getCompilationUnit(TypeElement typeElement) {
        TreePath treePath = trees.getPath(typeElement);
        if (treePath != null) {
            return parse(treePath.getCompilationUnit().toString());
        } else {
            try {
                return typeElementDecompiler.decompileOrEmpty(typeElement).flatMap(source -> javaParser.parse(source).getResult());
            } catch (Exception e) {
                Logger.warn(e);
                throw new RuntimeException(e);
            }
        }
    }

    public Optional<CompilationUnit> getCompilationUnit(AnnotationExpr annotationExpr) {
        TypeElement typeElement = elements.getTypeElement(getQualifiedName(annotationExpr));
        return getCompilationUnit(typeElement);
    }

    public Optional<CompilationUnit> getCompilationUnit(String qualifiedName) {
        TypeElement typeElement = elements.getTypeElement(qualifiedName);
        return getCompilationUnit(typeElement);
    }

    public Optional<Class<?>> getClass(String qualifiedName) {
        try {
            return Optional.of(Class.forName(qualifiedName, false, classLoader));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }

    public CompilationUnit getCompilationUnitOrError(AnnotationExpr annotationExpr) {
        return getCompilationUnit(annotationExpr).orElseThrow(() -> new InjectionProcessException(InjectionProcessErrorType.CANNOT_PARSER_SOURCE_CODE.bind(getQualifiedName(annotationExpr))));
    }

    public CompilationUnit getCompilationUnitOrError(TypeElement typeElement) {
        return getCompilationUnit(typeElement).orElseThrow(() -> new InjectionProcessException(InjectionProcessErrorType.CANNOT_PARSER_SOURCE_CODE.bind((typeElement).getQualifiedName())));
    }

    public CompilationUnit getCompilationUnitOrError(String qualifiedName) {
        return getCompilationUnit(qualifiedName).orElseThrow(() -> new InjectionProcessException(InjectionProcessErrorType.CANNOT_PARSER_SOURCE_CODE.bind(qualifiedName)));
    }

    public Stream<ResolvedType> getMethodReturnResolvedType(MethodDeclaration methodDeclaration) {
        return methodDeclaration.findAll(ReturnStmt.class).stream()
                .map(ReturnStmt::getExpression)
                .flatMap(Optional::stream)
                .filter(expression -> !expression.isMethodReferenceExpr())
                .map(javaSymbolSolver::calculateType);
    }

    public Stream<ResolvedReferenceType> getMethodReturnResolvedReferenceType(MethodDeclaration methodDeclaration) {
        return getMethodReturnResolvedType(methodDeclaration)
                .filter(ResolvedType::isReferenceType)
                .map(ResolvedType::asReferenceType);
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
                .map(element -> parse((TypeElement) element).orElseThrow(() -> new InjectionProcessException(InjectionProcessErrorType.CANNOT_PARSER_SOURCE_CODE.bind(((TypeElement) element).getQualifiedName()))))
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
                .orElseThrow(() -> new InjectionProcessException(InjectionProcessErrorType.PUBLIC_CLASS_NOT_EXIST.bind(compilationUnit.toString())));
    }

    private Optional<AnnotationDeclaration> getPublicAnnotationDeclaration(CompilationUnit compilationUnit) {
        return compilationUnit.getTypes().stream()
                .filter(typeDeclaration -> typeDeclaration.hasModifier(Modifier.Keyword.PUBLIC))
                .filter(BodyDeclaration::isAnnotationDeclaration)
                .map(BodyDeclaration::asAnnotationDeclaration)
                .findFirst();
    }

    public AnnotationDeclaration getPublicAnnotationDeclarationOrError(CompilationUnit compilationUnit) {
        return getPublicAnnotationDeclaration(compilationUnit)
                .orElseThrow(() -> new InjectionProcessException(InjectionProcessErrorType.PUBLIC_ANNOTATION_NOT_EXIST.bind(compilationUnit.toString())));
    }

    public String getQualifiedName(ClassOrInterfaceDeclaration classOrInterfaceDeclaration) {
        ResolvedReferenceTypeDeclaration resolvedReferenceTypeDeclaration = javaSymbolSolver.resolveDeclaration(classOrInterfaceDeclaration, ResolvedReferenceTypeDeclaration.class);
        return resolvedReferenceTypeDeclaration.getQualifiedName();
    }

    public String getQualifiedName(AnnotationExpr annotationExpr) {
        ResolvedAnnotationDeclaration resolvedAnnotationDeclaration = javaSymbolSolver.resolveDeclaration(annotationExpr, ResolvedAnnotationDeclaration.class);
        return resolvedAnnotationDeclaration.getQualifiedName();
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

    public String getQualifiedName(MethodDeclaration methodDeclaration) {
        String qualifiedName = getQualifiedName(methodDeclaration.getType());
        if (qualifiedName.equals(Mono.class.getName())) {
            return methodDeclaration.getType().resolve().asReferenceType().getTypeParametersMap().get(0).b.asReferenceType().getQualifiedName();
        }
        return qualifiedName;
    }

    public ResolvedType getResolvedType(Type type) {
        try {
            return javaSymbolSolver.toResolvedType(type, ResolvedReferenceType.class);
        } catch (UnsolvedSymbolException e) {
            if (type.isClassOrInterfaceType() && type.hasScope()) {
                return getResolvedInnerType(type.asClassOrInterfaceType());
            }
            throw e;
        }
    }

    public ResolvedType getResolvedInnerType(ClassOrInterfaceType classOrInterfaceType) {
        if (classOrInterfaceType.hasScope()) {
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
                    .map(MemberValuePair::getValue)
                    .map(expression -> {
                                if (expression.isFieldAccessExpr()) {
                                    return expression.asFieldAccessExpr().resolve().toAst()
                                            .flatMap(Node::findCompilationUnit).stream()
                                            .flatMap(compilationUnit -> getPublicClassOrInterfaceDeclarationOrError(compilationUnit).getFields().stream())
                                            .flatMap(fieldDeclaration -> fieldDeclaration.getVariables().stream())
                                            .filter(variableDeclarator -> variableDeclarator.getNameAsString().equals(expression.asFieldAccessExpr().getNameAsString()))
                                            .findFirst()
                                            .flatMap(VariableDeclarator::getInitializer)
                                            .orElseThrow(() -> new RuntimeException("field " + expression.asFieldAccessExpr().getNameAsString() + " not found"));
                                } else {
                                    return expression;
                                }
                            }
                    );
        }
        return Optional.empty();
    }

    public boolean isInjectFieldSetter(MethodDeclaration methodDeclaration) {
        return methodDeclaration.getBody().stream()
                .flatMap(blockStmt -> blockStmt.findAll(AssignExpr.class).stream())
                .filter(assignExpr -> assignExpr.getTarget().isFieldAccessExpr())
                .map(assignExpr -> assignExpr.getTarget().asFieldAccessExpr().resolve())
                .filter(ResolvedDeclaration::isField)
                .flatMap(resolvedValueDeclaration -> resolvedValueDeclaration.asField().toAst().stream())
                .map(node -> (FieldDeclaration) node)
                .anyMatch(fieldDeclaration -> fieldDeclaration.isAnnotationPresent(Inject.class));
    }

    public void importAllClassOrInterfaceType(ClassOrInterfaceDeclaration classOrInterfaceDeclaration, ClassOrInterfaceDeclaration sourceClassOrInterfaceDeclaration) {
        sourceClassOrInterfaceDeclaration.findCompilationUnit().stream()
                .flatMap(compilationUnit -> compilationUnit.getImports().stream())
                .forEach(importDeclaration ->
                        classOrInterfaceDeclaration.findCompilationUnit()
                                .ifPresent(compilationUnit -> compilationUnit.addImport(importDeclaration))
                );
    }

    public Optional<String> getScopedAnnotationName(NodeWithAnnotations<?> nodeWithAnnotations) {
        return Stream.ofNullable(nodeWithAnnotations.getAnnotations())
                .flatMap(NodeList::stream)
                .flatMap(annotationExpr ->
                        getCompilationUnit(annotationExpr)
                                .flatMap(this::getPublicAnnotationDeclaration)
                                .filter(annotationDeclaration -> annotationDeclaration.isAnnotationPresent(NormalScope.class))
                                .map(annotationDeclaration ->
                                        annotationDeclaration.getFullyQualifiedName()
                                                .orElseGet(() -> getQualifiedName(annotationExpr))
                                )
                                .stream()
                )
                .findFirst();
    }

    public Stream<String> getExtendedTypes(ClassOrInterfaceDeclaration classOrInterfaceDeclaration) {
        return Stream
                .concat(
                        classOrInterfaceDeclaration.getExtendedTypes().stream().map(this::getQualifiedName),
                        classOrInterfaceDeclaration.getExtendedTypes().stream()
                                .flatMap(this::getExtendedTypes)
                )
                .distinct()
                .filter(name -> !name.equals(Object.class.getName()));
    }

    public Stream<String> getExtendedTypes(ClassOrInterfaceType classOrInterfaceType) {
        return classOrInterfaceType.resolve().asReferenceType().getTypeDeclaration()
                .flatMap(resolvedReferenceTypeDeclaration -> getClass(resolvedReferenceTypeDeclaration.getQualifiedName()))
                .stream()
                .flatMap(this::getExtendedTypeNames);
    }

    public Stream<String> getExtendedTypeNames(Class<?> clazz) {
        return Stream.ofNullable(clazz.getSuperclass())
                .flatMap(superClazz ->
                        Stream
                                .concat(
                                        Stream.of(superClazz.getName()),
                                        getExtendedTypeNames(superClazz)
                                )
                );
    }

    public Stream<String> getImplementedTypes(ClassOrInterfaceDeclaration classOrInterfaceDeclaration) {
        return Stream
                .concat(
                        classOrInterfaceDeclaration.getImplementedTypes().stream().map(this::getQualifiedName),
                        classOrInterfaceDeclaration.getImplementedTypes().stream()
                                .flatMap(this::getImplementedTypes)
                )
                .distinct()
                .filter(name -> !name.equals(Object.class.getName()));
    }

    public Stream<String> getImplementedTypes(ClassOrInterfaceType classOrInterfaceType) {
        return classOrInterfaceType.resolve().asReferenceType().getTypeDeclaration()
                .flatMap(resolvedReferenceTypeDeclaration -> getClass(resolvedReferenceTypeDeclaration.getQualifiedName()))
                .stream()
                .flatMap(this::getImplementedTypes);
    }

    public Stream<String> getImplementedTypes(Class<?> clazz) {
        return Stream.ofNullable(clazz.getInterfaces())
                .flatMap(Arrays::stream)
                .flatMap(interfaceClazz ->
                        Stream
                                .concat(
                                        Stream.of(interfaceClazz.getName()),
                                        getImplementedTypes(interfaceClazz)
                                )
                );
    }
}
