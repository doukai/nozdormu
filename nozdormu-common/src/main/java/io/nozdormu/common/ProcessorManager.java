package io.nozdormu.common;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.*;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;
import com.sun.source.util.Trees;
import io.nozdormu.spi.decompiler.TypeElementDecompiler;
import io.nozdormu.spi.decompiler.TypeElementDecompilerProvider;
import io.nozdormu.spi.error.InjectionProcessErrorType;
import io.nozdormu.spi.error.InjectionProcessException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.*;
import java.io.*;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static javax.lang.model.element.ElementKind.CLASS;

public class ProcessorManager {

    private static final Logger logger = LoggerFactory.getLogger(ProcessorManager.class);
    private static final Map<String, CompilationUnit> COMPILATION_UNIT_CACHE = new ConcurrentHashMap<>();

    private final ProcessingEnvironment processingEnv;
    private RoundEnvironment roundEnv;
    private final Trees trees;
    private final Filer filer;
    private final Elements elements;
    private final JavaParser javaParser;
    private final JavaSymbolSolver javaSymbolSolver;
    private final CombinedTypeSolver combinedTypeSolver;
    private final TypeElementDecompiler typeElementDecompiler;
    private Path decompileCacheDir;

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
            this.combinedTypeSolver.add(javaParserTypeSolver);
        }
        Path testSourcePath = getTestSourcePath(generatedSourcePath);
        if (Files.exists(testSourcePath)) {
            JavaParserTypeSolver testJavaParserTypeSolver = new JavaParserTypeSolver(testSourcePath);
            this.combinedTypeSolver.add(testJavaParserTypeSolver);
        }
        JavaParserTypeSolver generatedJavaParserTypeSolver = new JavaParserTypeSolver(generatedSourcePath);
        this.combinedTypeSolver.add(generatedJavaParserTypeSolver);
        String rootProjectDirOption = processingEnv.getOptions().get("rootProjectDir");
        if (rootProjectDirOption != null && !rootProjectDirOption.isEmpty()) {
            Path rootProjectDir = Paths.get(rootProjectDirOption);
            if (Files.exists(rootProjectDir)) {
                try (Stream<Path> stream = Files.walk(rootProjectDir, 1)) {
                    stream.filter(Files::isDirectory)
                            .forEach(path -> {
                                        Path javaPath = path.resolve("src/main/java");
                                        if (Files.exists(javaPath)) {
                                            JavaParserTypeSolver javaParserTypeSolver = new JavaParserTypeSolver(javaPath);
                                            this.combinedTypeSolver.add(javaParserTypeSolver);
                                        }
                                        Path testJavaPath = path.resolve("src/test/java");
                                        if (Files.exists(testJavaPath)) {
                                            JavaParserTypeSolver testJavaParserTypeSolver = new JavaParserTypeSolver(testJavaPath);
                                            this.combinedTypeSolver.add(testJavaParserTypeSolver);
                                        }
                                    }
                            );
                } catch (IOException ignore) {
                }
            }
        }
        ReflectionTypeSolver reflectionTypeSolver = new ReflectionTypeSolver();
        this.combinedTypeSolver.add(reflectionTypeSolver);
        ClassLoaderTypeSolver classLoaderTypeSolver = new ClassLoaderTypeSolver(classLoader);
        this.combinedTypeSolver.add(classLoaderTypeSolver);
        this.javaSymbolSolver = new JavaSymbolSolver(this.combinedTypeSolver);
        this.javaParser = new JavaParser();
        this.javaParser.getParserConfiguration().setSymbolResolver(this.javaSymbolSolver);
        this.typeElementDecompiler = TypeElementDecompilerProvider.load(classLoader);
        String decompileCacheDirOption = processingEnv.getOptions().get("decompileCacheDir");
        if (decompileCacheDirOption != null && !decompileCacheDirOption.isEmpty()) {
            this.decompileCacheDir = Paths.get(decompileCacheDirOption);
        }
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
            logger.info("generated source path: {}", generatedSourcePath.toString());
            return generatedSourcePath;
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "unable to determine generated source path.");
            throw new RuntimeException(e);
        }
    }

    private Path getSourcePath(Path generatedSourcePath) {
        Path sourcePath = generatedSourcePath.getParent().getParent().getParent().getParent().getParent().getParent().resolve("src/main/java");
        logger.info("source path: {}", sourcePath);
        return sourcePath;
    }

    private Path getTestSourcePath(Path generatedSourcePath) {
        Path sourcePath = generatedSourcePath.getParent().getParent().getParent().getParent().getParent().getParent().resolve("src/test/java");
        logger.info("test source path: {}", sourcePath);
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
                                .filter(packageElement -> !packageElement.isUnnamed())
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
                                logger.info("{} compilationUnit build success", qualifiedName);
                            } catch (IOException e) {
                                logger.error(e.getMessage(), e);
                                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "java file create failed");
                                throw new RuntimeException(e);
                            }
                        }
                );
    }

    public void createResource(String fileName, String content) {
        try {
            Writer writer = filer.createResource(StandardLocation.CLASS_OUTPUT, "", fileName).openWriter();
            writer.write(content);
            writer.close();
            logger.info("{} fileName build success", fileName);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "resource file create failed");
            throw new RuntimeException(e);
        }
    }

    public FileObject getResource(String fileName) {
        try {
            return processingEnv.getFiler().getResource(StandardLocation.CLASS_OUTPUT, "", fileName);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void cacheDecompile(TypeElement typeElement, String source) {
        if (decompileCacheDir != null) {
            Path filePath = decompileCacheDir.resolve(typeElement.getQualifiedName().toString().replace('.', File.separatorChar) + ".java");
            try {
                if (!Files.exists(filePath.getParent())) {
                    Files.createDirectories(filePath.getParent());
                }
                Files.writeString(
                        filePath,
                        source,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE
                );
                logger.info("cache decompile: {}", typeElement.getQualifiedName());
            } catch (IOException ignore) {
            }
        }
    }

    public Optional<CompilationUnit> getCompilationUnit(TypeElement typeElement) {
        if (COMPILATION_UNIT_CACHE.containsKey(typeElement.getQualifiedName().toString())) {
            logger.info("{} decompile cache exist", typeElement.getQualifiedName());
            return Optional.of(COMPILATION_UNIT_CACHE.get(typeElement.getQualifiedName().toString()));
        }
        Optional<CompilationUnit> compilationUnitOptional = combinedTypeSolver.getRoot().tryToSolveType(typeElement.getQualifiedName().toString()).getDeclaration()
                .flatMap(AssociableToAST::toAst)
                .flatMap(Node::findCompilationUnit)
                .or(() ->
                        Optional.ofNullable(trees.getPath(typeElement))
                                .flatMap(treePath -> {
                                            JavaFileObject sourceFile = treePath.getCompilationUnit().getSourceFile();
                                            try {
                                                return Optional.of(sourceFile.getCharContent(true).toString());
                                            } catch (IOException e) {
                                                return Optional.empty();
                                            }
                                        }
                                )
                                .flatMap(source -> {
                                            ParseResult<CompilationUnit> parseResult = javaParser.parse(source);
                                            if (!parseResult.getProblems().isEmpty()) {
                                                throw new RuntimeException(parseResult.getProblems().stream()
                                                        .map(Problem::getMessage)
                                                        .collect(Collectors.joining(System.lineSeparator())));
                                            }
                                            return parseResult.getResult();
                                        }
                                )
                                .or(() -> {
                                            if (decompileCacheDir != null) {
                                                Path filePath = decompileCacheDir.resolve(typeElement.getQualifiedName().toString().replace('.', File.separatorChar) + ".java");
                                                try {
                                                    String decompileJava = Files.readString(filePath);
                                                    logger.info("{} decompile java file exist", typeElement.getQualifiedName());
                                                    ParseResult<CompilationUnit> parseResult = javaParser.parse(decompileJava);
                                                    if (!parseResult.getProblems().isEmpty()) {
                                                        throw new RuntimeException(parseResult.getProblems().stream()
                                                                .map(Problem::getMessage)
                                                                .collect(Collectors.joining(System.lineSeparator())));
                                                    }
                                                    return parseResult.getResult();
                                                } catch (IOException e) {
                                                    return Optional.empty();
                                                }
                                            } else {
                                                return Optional.empty();
                                            }
                                        }
                                )
                                .or(() -> {
                                            try {
                                                logger.info("{} decompile start", typeElement.getQualifiedName());
                                                return typeElementDecompiler.decompileOrEmpty(typeElement)
                                                        .flatMap(source -> {
                                                                    cacheDecompile(typeElement, source);
                                                                    ParseResult<CompilationUnit> parseResult = javaParser.parse(source);
                                                                    if (!parseResult.getProblems().isEmpty()) {
                                                                        throw new RuntimeException(parseResult.getProblems().stream()
                                                                                .map(Problem::getMessage)
                                                                                .collect(Collectors.joining(System.lineSeparator())));
                                                                    }
                                                                    logger.info("{} decompile success", typeElement.getQualifiedName());
                                                                    return parseResult.getResult();
                                                                }
                                                        );
                                            } catch (Exception e) {
                                                logger.info("{} decompile error", typeElement.getQualifiedName());
                                                throw new RuntimeException(e);
                                            }
                                        }
                                )
                );
        compilationUnitOptional
                .ifPresent(compilationUnit -> COMPILATION_UNIT_CACHE.put(typeElement.getQualifiedName().toString(), compilationUnit));
        return compilationUnitOptional;
    }


    public List<CompilationUnit> getCompilationUnitListWithAnnotationClass(Class<? extends Annotation> annotationClass) {
        return roundEnv.getElementsAnnotatedWith(annotationClass).stream()
                .filter(element -> element.getKind().equals(CLASS))
                .map(element -> getCompilationUnit((TypeElement) element).orElseThrow(() -> new InjectionProcessException(InjectionProcessErrorType.CANNOT_PARSER_SOURCE_CODE.bind(((TypeElement) element).getQualifiedName()))))
                .collect(Collectors.toList());
    }

    public Optional<CompilationUnit> getCompilationUnit(AnnotationExpr annotationExpr) {
        TypeElement typeElement = elements.getTypeElement(getQualifiedName(annotationExpr));
        return getCompilationUnit(typeElement);
    }

    public Optional<CompilationUnit> getCompilationUnit(String qualifiedName) {
        TypeElement typeElement = elements.getTypeElement(qualifiedName);
        return getCompilationUnit(typeElement);
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

    public Optional<ClassOrInterfaceDeclaration> getClassOrInterfaceDeclaration(String qualifiedName) {
        return getCompilationUnit(qualifiedName)
                .flatMap(compilationUnit ->
                        Stream
                                .concat(
                                        compilationUnit.getTypes().stream(),
                                        compilationUnit.getTypes().stream()
                                                .flatMap(typeDeclaration -> typeDeclaration.getMembers().stream())
                                )
                                .filter(BodyDeclaration::isClassOrInterfaceDeclaration)
                                .map(BodyDeclaration::asClassOrInterfaceDeclaration)
                                .filter(classOrInterfaceDeclaration ->
                                        classOrInterfaceDeclaration.getFullyQualifiedName()
                                                .orElse(classOrInterfaceDeclaration.getNameAsString())
                                                .equals(qualifiedName)
                                )
                                .findFirst()
                );
    }

    public ClassOrInterfaceDeclaration getClassOrInterfaceDeclarationOrError(String qualifiedName) {
        return getClassOrInterfaceDeclaration(qualifiedName).orElseThrow(() -> new InjectionProcessException(InjectionProcessErrorType.CLASS_NOT_EXIST.bind(qualifiedName)));
    }

    public Stream<ResolvedType> getNodeReturnResolvedType(Node node) {
        return node.findAll(ReturnStmt.class).stream()
                .map(ReturnStmt::getExpression)
                .flatMap(Optional::stream)
                .filter(expression -> !expression.isMethodReferenceExpr())
                .map(this::calculateType);
    }

    public Stream<ResolvedReferenceType> getNodeReturnResolvedReferenceType(Node node) {
        return getNodeReturnResolvedType(node)
                .filter(ResolvedType::isReferenceType)
                .map(ResolvedType::asReferenceType);
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

    public Optional<AnnotationDeclaration> getPublicAnnotationDeclaration(CompilationUnit compilationUnit) {
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

    public String getQualifiedName(ResolvedType resolvedType) {
        if (resolvedType.isPrimitive()) {
            return resolvedType.asPrimitive().getBoxTypeQName();
        } else if (resolvedType.isReferenceType()) {
            return resolvedType.asReferenceType().getQualifiedName();
        } else if (resolvedType.isArray()) {
            return getQualifiedName(resolvedType.asArrayType().getComponentType()) + "[]";
        }
        return resolvedType.describe();
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
        try {
            ResolvedReferenceType resolvedReferenceType = getResolvedType(type).asReferenceType();
            return resolvedReferenceType.getQualifiedName();
        } catch (RuntimeException e) {
            return type.getNameAsString();
        }
    }

    public String getQualifiedName(MethodDeclaration methodDeclaration) {
        String qualifiedName = getQualifiedName(methodDeclaration.getType());
        if (qualifiedName.equals(Mono.class.getName())) {
            return getResolvedType(methodDeclaration.getType()).asReferenceType().getTypeParametersMap().get(0).b.asReferenceType().getQualifiedName();
        }
        return qualifiedName;
    }

    public String getDescribe(Type type) {
        if (type.isClassOrInterfaceType()) {
            return getDescribe(type.asClassOrInterfaceType());
        } else if (type.isPrimitiveType()) {
            return getDescribe(type.asPrimitiveType().toBoxedType());
        } else if (type.isArrayType()) {
            return getDescribe(type.asArrayType().getElementType()) + "[]";
        }
        return type.asString();
    }

    public String getDescribe(ResolvedType resolvedType) {
        if (resolvedType.isPrimitive()) {
            return resolvedType.asPrimitive().getBoxTypeQName();
        } else if (resolvedType.isReferenceType()) {
            return resolvedType.asReferenceType().describe();
        } else if (resolvedType.isArray()) {
            return getDescribe(resolvedType.asArrayType().getComponentType()) + "[]";
        }
        return resolvedType.describe();
    }

    public String getDescribe(ClassOrInterfaceType type) {
        try {
            ResolvedReferenceType resolvedReferenceType = getResolvedType(type).asReferenceType();
            return resolvedReferenceType.describe();
        } catch (RuntimeException e) {
            return type.getNameAsString();
        }
    }

    public ResolvedType getResolvedType(Type type) {
        return javaSymbolSolver.toResolvedType(type, ResolvedReferenceType.class);
    }

    public ResolvedType calculateType(Expression expression) {
        try {
            return javaSymbolSolver.calculateType(expression);
        } catch (RuntimeException e) {
            return findResolvedType(expression);
        }
    }

    public Optional<Node> getDeclaratorNode(NameExpr nameExpr) {
        return getDeclaratorNode(nameExpr, nameExpr);
    }

    public Optional<Node> getDeclaratorNode(NameExpr nameExpr, Node node) {
        if (node.getParentNode().isPresent()) {
            Node parentNode = node.getParentNode().get();
            if (parentNode instanceof Statement) {
                Statement parentStatement = (Statement) parentNode;
                if (parentStatement.isBlockStmt()) {
                    return parentStatement.asBlockStmt().getStatements().stream()
                            .filter((Statement::isExpressionStmt))
                            .map((statement -> statement.asExpressionStmt().getExpression()))
                            .filter(expression ->
                                    expression.isVariableDeclarationExpr() &&
                                            expression.asVariableDeclarationExpr().getVariables().stream()
                                                    .anyMatch(variableDeclarator -> variableDeclarator.getNameAsString().equals(nameExpr.getNameAsString()))
                            )
                            .flatMap(expression ->
                                    expression.asVariableDeclarationExpr().getVariables().stream()
                                            .filter(variableDeclarator -> variableDeclarator.getNameAsString().equals(nameExpr.getNameAsString()))
                            )
                            .flatMap(variableDeclarator -> variableDeclarator.getInitializer().stream())
                            .map(expression -> (Node) expression)
                            .findFirst()
                            .or(() -> getDeclaratorNode(nameExpr, parentNode));
                } else {
                    getDeclaratorNode(nameExpr, parentNode);
                }
            } else if (parentNode instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration classOrInterfaceDeclaration = (ClassOrInterfaceDeclaration) parentNode;
                return classOrInterfaceDeclaration.getFieldByName(nameExpr.getNameAsString())
                        .filter(fieldDeclaration ->
                                fieldDeclaration.getVariables().stream()
                                        .anyMatch(variableDeclarator -> variableDeclarator.getNameAsString().equals(nameExpr.getNameAsString()))
                        )
                        .flatMap(fieldDeclaration ->
                                fieldDeclaration.getVariables().stream()
                                        .filter(variableDeclarator -> variableDeclarator.getNameAsString().equals(nameExpr.getNameAsString()))
                                        .findFirst()
                        )
                        .flatMap(VariableDeclarator::getInitializer)
                        .map(expression -> (Node) expression)
                        .or(() -> getDeclaratorNode(nameExpr, parentNode));
            } else if (parentNode instanceof CompilationUnit) {
                CompilationUnit compilationUnit = (CompilationUnit) parentNode;
                return compilationUnit.getImports().stream()
                        .filter(importDeclaration -> importDeclaration.getNameAsString().endsWith("." + nameExpr.getNameAsString()))
                        .map(importDeclaration -> getClassOrInterfaceDeclarationOrError(importDeclaration.getNameAsString()))
                        .map(expression -> (Node) expression)
                        .findFirst();
            } else {
                getDeclaratorNode(nameExpr, parentNode);
            }
        }
        return Optional.empty();
    }

    public Optional<MethodDeclaration> getMethodDeclaration(MethodCallExpr methodCallExpr) {
        if (methodCallExpr.getScope().stream().noneMatch(Expression::isThisExpr)) {
            ClassOrInterfaceDeclaration classOrInterfaceDeclaration;
            try {
                ResolvedReferenceType referenceType = calculateType(methodCallExpr.getScope().get()).asReferenceType();
                classOrInterfaceDeclaration = referenceType.getTypeDeclaration()
                        .flatMap(resolvedReferenceTypeDeclaration -> resolvedReferenceTypeDeclaration.toAst(ClassOrInterfaceDeclaration.class))
                        .orElseGet(() -> getPublicClassOrInterfaceDeclarationOrError(getCompilationUnitOrError(referenceType.getQualifiedName())));
            } catch (RuntimeException e) {
                classOrInterfaceDeclaration = methodCallExpr.getScope()
                        .filter(Expression::isNameExpr)
                        .flatMap(expression -> getDeclaratorNode(expression.asNameExpr()))
                        .filter(node -> node instanceof ClassOrInterfaceDeclaration)
                        .map(node -> (ClassOrInterfaceDeclaration) node)
                        .orElseThrow(() -> e);
            }
            return classOrInterfaceDeclaration.getMethods().stream()
                    .filter(methodDeclaration -> methodDeclaration.getNameAsString().equals(methodCallExpr.getNameAsString()))
                    .filter(methodDeclaration -> methodDeclaration.getParameters().size() == methodCallExpr.getArguments().size())
                    .filter(methodDeclaration ->
                            IntStream.range(0, methodCallExpr.getArguments().size())
                                    .allMatch(index -> {
                                                try {
                                                    ResolvedType resolvedType = calculateType(methodCallExpr.getArgument(index));
                                                    if (resolvedType.isPrimitive() && methodDeclaration.getParameter(index).getType().isPrimitiveType()) {
                                                        return resolvedType.asPrimitive().name().toLowerCase().equals(methodDeclaration.getParameter(index).getType().asString());
                                                    } else if (resolvedType.isReferenceType() && methodDeclaration.getParameter(index).getType().isReferenceType()) {
                                                        return getResolvedType(methodDeclaration.getParameter(index).getType()).isAssignableBy(resolvedType.asReferenceType());
                                                    }
                                                    return true;
                                                } catch (RuntimeException e) {
                                                    return true;
                                                }
                                            }
                                    )
                    )
                    .findFirst();
        } else {
            return methodCallExpr.findCompilationUnit()
                    .flatMap(this::getPublicClassOrInterfaceDeclaration).stream()
                    .flatMap(classOrInterfaceDeclaration -> classOrInterfaceDeclaration.getMethods().stream())
                    .filter(methodDeclaration -> methodDeclaration.getNameAsString().equals(methodCallExpr.getNameAsString()))
                    .filter(methodDeclaration -> methodDeclaration.getParameters().size() == methodCallExpr.getArguments().size())
                    .filter(methodDeclaration ->
                            IntStream.range(0, methodCallExpr.getArguments().size())
                                    .allMatch(index -> {
                                                try {
                                                    ResolvedType resolvedType = calculateType(methodCallExpr.getArgument(index));
                                                    if (resolvedType.isPrimitive() && methodDeclaration.getParameter(index).getType().isPrimitiveType()) {
                                                        return resolvedType.asPrimitive().name().toLowerCase().equals(methodDeclaration.getParameter(index).getType().asString());
                                                    } else if (resolvedType.isReferenceType() && methodDeclaration.getParameter(index).getType().isReferenceType()) {
                                                        return getResolvedType(methodDeclaration.getParameter(index).getType()).isAssignableBy(resolvedType.asReferenceType());
                                                    }
                                                    return true;
                                                } catch (RuntimeException e) {
                                                    return true;
                                                }
                                            }
                                    )
                    )
                    .findFirst();
        }
    }

    public ResolvedType findResolvedType(Expression expression) {
        if (expression.isMethodCallExpr()) {
            return getMethodDeclaration(expression.asMethodCallExpr())
                    .map(methodDeclaration -> getResolvedType(methodDeclaration.getType()))
                    .orElseThrow(() -> new UnsolvedSymbolException(expression.toString()));
        } else if (expression.isFieldAccessExpr()) {
            return calculateType(expression.asFieldAccessExpr().getScope()).asReferenceType().getDeclaredFields().stream()
                    .filter(resolvedFieldDeclaration -> resolvedFieldDeclaration.getName().equals(expression.asFieldAccessExpr().getNameAsString()))
                    .findFirst()
                    .map(ResolvedValueDeclaration::getType)
                    .orElseThrow(() -> new UnsolvedSymbolException(expression.toString()));
        } else {
            return getResolvedType(expression)
                    .filter(ResolvedType::isReferenceType)
                    .orElseThrow(() -> new UnsolvedSymbolException(expression.toString()));
        }
    }

    public ResolvedDeclaration getResolvedDeclaration(Node node) {
        return javaSymbolSolver.resolveDeclaration(node, ResolvedDeclaration.class);
    }

    public Optional<ResolvedType> getResolvedType(Node node) {
        ResolvedDeclaration resolvedDeclaration = getResolvedDeclaration(node);
        if (resolvedDeclaration.isField()) {
            return Optional.of(((JavaParserFieldDeclaration) resolvedDeclaration).getType());
        } else if (resolvedDeclaration.isMethod()) {
            return Optional.of(((JavaParserMethodDeclaration) resolvedDeclaration).getReturnType());
        } else if (resolvedDeclaration.isVariable()) {
            return Optional.of(((JavaParserVariableDeclaration) resolvedDeclaration).getType());
        } else if (resolvedDeclaration.isEnumConstant()) {
            return Optional.of(((JavaParserEnumConstantDeclaration) resolvedDeclaration).getType());
        } else if (resolvedDeclaration.isParameter()) {
            return Optional.of(((JavaParserParameterDeclaration) resolvedDeclaration).getType());
        }
        return Optional.empty();
    }

    public String resolveMethodDeclarationReturnTypeQualifiedName(MethodCallExpr methodCallExpr) {
        try {
            return getQualifiedName(calculateType(methodCallExpr));
        } catch (RuntimeException e) {
            return getMethodDeclaration(methodCallExpr)
                    .map(methodDeclaration -> getQualifiedName(methodDeclaration.getType()))
                    .orElseThrow(() -> new UnsolvedSymbolException(methodCallExpr.toString()));
        }
    }

    public String resolveMethodDeclarationReturnTypeDescribe(MethodCallExpr methodCallExpr) {
        try {
            return getDescribe(calculateType(methodCallExpr));
        } catch (RuntimeException e) {
            return getMethodDeclaration(methodCallExpr)
                    .map(methodDeclaration -> getDescribe(methodDeclaration.getType()))
                    .orElseThrow(() -> new UnsolvedSymbolException(methodCallExpr.toString()));
        }
    }

    public Stream<String> resolveMethodDeclarationParameterTypeNames(MethodCallExpr methodCallExpr) {
        return getMethodDeclaration(methodCallExpr)
                .map(methodDeclaration ->
                        methodDeclaration.getParameters().stream().map(parameter -> {
                                    if (parameter.getType().isPrimitiveType()) {
                                        return parameter.getType().asPrimitiveType().toBoxedType().getNameAsString();
                                    } else if (parameter.getType().isClassOrInterfaceType()) {
                                        return parameter.getType().asClassOrInterfaceType().getNameAsString();
                                    } else {
                                        return parameter.getTypeAsString();
                                    }
                                }
                        )
                )
                .orElseThrow(() -> new UnsolvedSymbolException(methodCallExpr.toString()));
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
                                    return getResolvedDeclaration(expression.asFieldAccessExpr()).toAst()
                                            .flatMap(Node::findCompilationUnit).stream()
                                            .flatMap(compilationUnit -> getPublicClassOrInterfaceDeclarationOrError(compilationUnit).getFields().stream())
                                            .flatMap(fieldDeclaration -> fieldDeclaration.getVariables().stream())
                                            .filter(variableDeclarator -> variableDeclarator.getNameAsString().equals(expression.asFieldAccessExpr().getNameAsString()))
                                            .findFirst()
                                            .flatMap(VariableDeclarator::getInitializer)
                                            .orElseThrow(() -> new UnsolvedSymbolException(expression.toString()));
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
                .map(assignExpr -> getResolvedDeclaration(assignExpr.getTarget().asFieldAccessExpr()))
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
        if (nodeWithAnnotations.isAnnotationPresent(RequestScoped.class)) {
            return Optional.of(RequestScoped.class.getName());
        } else if (nodeWithAnnotations.isAnnotationPresent(SessionScoped.class)) {
            return Optional.of(SessionScoped.class.getName());
        } else if (nodeWithAnnotations.isAnnotationPresent(TransactionScoped.class)) {
            return Optional.of(TransactionScoped.class.getName());
        }
        return Optional.empty();
    }

    public Stream<String> getExtendedTypes(ClassOrInterfaceDeclaration classOrInterfaceDeclaration) {
        return Stream
                .concat(
                        classOrInterfaceDeclaration.getExtendedTypes().stream()
                                .map(this::getQualifiedName),
                        classOrInterfaceDeclaration.getExtendedTypes().stream()
                                .flatMap(this::getExtendedTypes)
                )
                .distinct();
    }

    public Stream<String> getExtendedTypes(ClassOrInterfaceType classOrInterfaceType) {
        return getResolvedType(classOrInterfaceType).asReferenceType().getTypeDeclaration().stream()
                .flatMap(resolvedReferenceTypeDeclaration -> resolvedReferenceTypeDeclaration.getAllAncestors().stream())
                .map(ResolvedReferenceType::getQualifiedName);
    }

    public Stream<String> getImplementedTypes(ClassOrInterfaceDeclaration classOrInterfaceDeclaration) {
        return Stream
                .concat(
                        classOrInterfaceDeclaration.getImplementedTypes().stream()
                                .map(this::getQualifiedName),
                        classOrInterfaceDeclaration.getImplementedTypes().stream()
                                .flatMap(this::getImplementedTypes)
                )
                .distinct();
    }

    public Stream<String> getImplementedTypes(ClassOrInterfaceType classOrInterfaceType) {
        return getResolvedType(classOrInterfaceType).asReferenceType().getTypeDeclaration().stream()
                .flatMap(resolvedReferenceTypeDeclaration -> resolvedReferenceTypeDeclaration.getAllAncestors().stream())
                .map(ResolvedReferenceType::getQualifiedName);
    }
}
