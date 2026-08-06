package group.zn.zero.codegen.model;

import group.zn.zero.codegen.dsl.ProtocolDslDocument;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 代码生成请求。
 *
 * @param document 协议 DSL 文档。
 * @param outputDir 默认输出目录。
 * @param languages 目标语言列表。
 * @param generateBoImpl 是否生成 BO 默认实现模板。
 * @param languageOutputDirs 目标语言输出目录覆盖表。
 * @param languageNamespaces 目标语言命名空间覆盖表。
 * @author zn
 */
public record CodegenRequest(
        ProtocolDslDocument document,
        Path outputDir,
        List<CodegenLanguage> languages,
        boolean generateBoImpl,
        Map<CodegenLanguage, Path> languageOutputDirs,
        Map<CodegenLanguage, String> languageNamespaces,
        Map<CodegenLanguage, String> dtoSuffixes,
        Map<JavaArtifactKind, Path> javaArtifactOutputDirs,
        Map<JavaArtifactKind, String> javaArtifactPackages) {

    /**
     * 默认协议 DTO 类名后缀。
     */
    public static final String DEFAULT_DTO_SUFFIX = "DTO";

    /**
     * 创建默认 Java 代码生成请求。
     *
     * @param document 协议 DSL 文档。
     * @param outputDir 输出目录。
     */
    public CodegenRequest(final ProtocolDslDocument document, final Path outputDir) {
        this(document, outputDir, List.of(CodegenLanguage.JAVA), false);
    }

    /**
     * 创建指定语言的代码生成请求。
     *
     * @param document 协议 DSL 文档。
     * @param outputDir 输出目录。
     * @param languages 目标语言列表。
     */
    public CodegenRequest(
            final ProtocolDslDocument document,
            final Path outputDir,
            final List<CodegenLanguage> languages) {
        this(document, outputDir, languages, false);
    }

    /**
     * 创建代码生成请求。
     *
     * @param document 协议 DSL 文档。
     * @param outputDir 默认输出目录。
     * @param languages 目标语言列表。
     * @param generateBoImpl 是否生成 BO 默认实现模板。
     */
    public CodegenRequest(
            final ProtocolDslDocument document,
            final Path outputDir,
            final List<CodegenLanguage> languages,
            final boolean generateBoImpl) {
        this(document, outputDir, languages, generateBoImpl, Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    /**
     * 创建代码生成请求。
     *
     * @param document 协议 DSL 文档。
     * @param outputDir 默认输出目录。
     * @param languages 目标语言列表。
     * @param generateBoImpl 是否生成 BO 默认实现模板。
     * @param languageOutputDirs 目标语言输出目录覆盖表。
     * @param languageNamespaces 目标语言命名空间覆盖表。
     */
    public CodegenRequest(
            final ProtocolDslDocument document,
            final Path outputDir,
            final List<CodegenLanguage> languages,
            final boolean generateBoImpl,
            final Map<CodegenLanguage, Path> languageOutputDirs,
            final Map<CodegenLanguage, String> languageNamespaces) {
        this(document, outputDir, languages, generateBoImpl, languageOutputDirs, languageNamespaces, Map.of());
    }

    /**
     * 创建代码生成请求。
     *
     * @param document 协议 DSL 文档。
     * @param outputDir 默认输出目录。
     * @param languages 目标语言列表。
     * @param generateBoImpl 是否生成 BO 默认实现模板。
     * @param languageOutputDirs 目标语言输出目录覆盖表。
     * @param languageNamespaces 目标语言命名空间覆盖表。
     * @param dtoSuffixes DTO 类名后缀覆盖表。
     */
    public CodegenRequest(
            final ProtocolDslDocument document,
            final Path outputDir,
            final List<CodegenLanguage> languages,
            final boolean generateBoImpl,
            final Map<CodegenLanguage, Path> languageOutputDirs,
            final Map<CodegenLanguage, String> languageNamespaces,
            final Map<CodegenLanguage, String> dtoSuffixes) {
        this(document, outputDir, languages, generateBoImpl, languageOutputDirs, languageNamespaces, dtoSuffixes,
                Map.of(), Map.of());
    }

    /**
     * 创建代码生成请求。
     *
     * @throws NullPointerException 当文档、输出目录、目标语言列表或覆盖表为空时抛出。
     * @throws IllegalArgumentException 当目标语言列表为空时抛出。
     */
    public CodegenRequest {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(outputDir, "outputDir");
        Objects.requireNonNull(languages, "languages");
        Objects.requireNonNull(languageOutputDirs, "languageOutputDirs");
        Objects.requireNonNull(languageNamespaces, "languageNamespaces");
        Objects.requireNonNull(dtoSuffixes, "dtoSuffixes");
        Objects.requireNonNull(javaArtifactOutputDirs, "javaArtifactOutputDirs");
        Objects.requireNonNull(javaArtifactPackages, "javaArtifactPackages");
        if (languages.isEmpty()) {
            throw new IllegalArgumentException("languages must not be empty");
        }
        validateJavaArtifactPackages(javaArtifactPackages);
        languages = List.copyOf(new LinkedHashSet<>(languages));
        languageOutputDirs = Map.copyOf(new LinkedHashMap<>(languageOutputDirs));
        languageNamespaces = Map.copyOf(new LinkedHashMap<>(languageNamespaces));
        dtoSuffixes = Map.copyOf(new LinkedHashMap<>(dtoSuffixes));
        javaArtifactOutputDirs = Map.copyOf(new LinkedHashMap<>(javaArtifactOutputDirs));
        javaArtifactPackages = Map.copyOf(new LinkedHashMap<>(javaArtifactPackages));
    }

    /**
     * 返回目标语言列表。
     *
     * @return 不可变、有序、不为空、线程安全的目标语言列表。
     */
    @Override
    public List<CodegenLanguage> languages() {
        return languages;
    }

    /**
     * 返回指定语言的输出目录。
     *
     * @param language 目标语言；不可为空。
     * @return 输出目录；未覆盖时返回默认输出目录；线程安全。
     */
    public Path outputDir(final CodegenLanguage language) {
        CodegenLanguage target = Objects.requireNonNull(language, "language");
        Path explicit = languageOutputDirs.get(target);
        if (explicit != null) {
            return explicit;
        }
        String folder = target.defaultOutputFolder();
        if (folder.isBlank()) {
            return outputDir;
        }
        return outputDir.resolve(folder);
    }

    /**
     * 返回指定语言的命名空间。
     *
     * @param language 目标语言；不可为空。
     * @return 命名空间；未覆盖时返回 DSL namespace；线程安全。
     */
    public String namespace(final CodegenLanguage language) {
        return languageNamespaces.getOrDefault(Objects.requireNonNull(language, "language"), document.namespace());
    }

    /**
     * 返回指定语言的 DTO 类名后缀。
     *
     * @param language 目标语言；不可为空。
     * @return DTO 类名后缀；默认为 {@value #DEFAULT_DTO_SUFFIX}；可为空字符串；线程安全。
     */
    public String dtoSuffix(final CodegenLanguage language) {
        return dtoSuffixes.getOrDefault(Objects.requireNonNull(language, "language"), DEFAULT_DTO_SUFFIX);
    }

    /**
     * 返回 Java 指定生成物类型的输出目录。
     *
     * @param kind Java 生成物类型；不允许为空。
     * @return 输出目录；未覆盖时按 Java 语言输出目录和生成物类型派生；线程安全。
     */
    public Path javaOutputDir(final JavaArtifactKind kind) {
        JavaArtifactKind target = Objects.requireNonNull(kind, "kind");
        Path explicit = javaArtifactOutputDirs.get(target);
        if (explicit != null) {
            return explicit;
        }
        return switch (target) {
            case CODEC -> javaOutputDir(JavaArtifactKind.DTO);
            case BO_IMPL -> javaOutputDir(JavaArtifactKind.BO);
            case DISPATCHER -> javaOutputDir(JavaArtifactKind.PROTOCOL);
            default -> outputDir(CodegenLanguage.JAVA);
        };
    }

    /**
     * 返回 Java 指定生成物类型的包名。
     *
     * @param kind Java 生成物类型；不允许为空。
     * @return 包名；未覆盖时按 Java 基础包名和生成物类型派生；线程安全。
     */
    public String javaPackage(final JavaArtifactKind kind) {
        JavaArtifactKind target = Objects.requireNonNull(kind, "kind");
        String explicit = javaArtifactPackages.get(target);
        if (explicit != null) {
            return explicit;
        }
        return switch (target) {
            case DTO -> namespace(CodegenLanguage.JAVA) + ".dto";
            case CODEC -> javaPackage(JavaArtifactKind.DTO) + ".codec";
            case PROTOCOL -> namespace(CodegenLanguage.JAVA) + ".protocol";
            case BO -> namespace(CodegenLanguage.JAVA) + ".bo";
            case BO_IMPL -> javaPackage(JavaArtifactKind.BO) + ".impl";
            case DISPATCHER -> javaPackage(JavaArtifactKind.PROTOCOL) + ".dispatch";
        };
    }

    private static void validateJavaArtifactPackages(final Map<JavaArtifactKind, String> packages) {
        for (Map.Entry<JavaArtifactKind, String> entry : packages.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                throw new IllegalArgumentException("java artifact package must not be blank: " + entry.getKey());
            }
        }
    }
}
