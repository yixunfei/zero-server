package group.zn.zero.codegen;

import group.zn.zero.codegen.model.CodegenLanguage;
import group.zn.zero.codegen.model.JavaArtifactKind;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 协议代码生成工具参数。
 *
 * @param inputPaths `.si` 文件或目录列表；不可为空。
 * @param outputDir 默认生成输出目录；不可为空。
 * @param namespace Java 生成包名；不可为空。
 * @param protoIdPath protoId 文件路径；可为空。
 * @param generateBoImpl 是否生成 `XXXEventBOImp` 默认实现模板。
 * @param languages 目标语言列表；不可为空。
 * @param languageOutputDirs 目标语言输出目录覆盖表。
 * @param languageNamespaces 目标语言命名空间覆盖表。
 * @author zn
 */
public record ProtocolCodegenOptions(
        List<Path> inputPaths,
        Path outputDir,
        String namespace,
        Path protoIdPath,
        boolean generateBoImpl,
        List<CodegenLanguage> languages,
        Map<CodegenLanguage, Path> languageOutputDirs,
        Map<CodegenLanguage, String> languageNamespaces,
        Map<CodegenLanguage, String> dtoSuffixes,
        Map<JavaArtifactKind, Path> javaArtifactOutputDirs,
        Map<JavaArtifactKind, String> javaArtifactPackages) {

    /**
     * 创建默认 Java 协议代码生成工具参数。
     *
     * @param inputPaths `.si` 文件或目录列表；不可为空。
     * @param outputDir 默认生成输出目录；不可为空。
     * @param namespace Java 生成包名；不可为空。
     * @param protoIdPath protoId 文件路径；可为空。
     * @param generateBoImpl 是否生成 `XXXEventBOImp` 默认实现模板。
     */
    public ProtocolCodegenOptions(
            final List<Path> inputPaths,
            final Path outputDir,
            final String namespace,
            final Path protoIdPath,
            final boolean generateBoImpl) {
        this(inputPaths, outputDir, namespace, protoIdPath, generateBoImpl,
                List.of(CodegenLanguage.JAVA), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    /**
     * 创建协议代码生成工具参数。
     *
     * @param inputPaths `.si` 文件或目录列表；不可为空。
     * @param outputDir 默认生成输出目录；不可为空。
     * @param namespace Java 生成包名；不可为空。
     * @param protoIdPath protoId 文件路径；可为空。
     * @param generateBoImpl 是否生成 `XXXEventBOImp` 默认实现模板。
     * @param languages 目标语言列表；不可为空。
     * @param languageOutputDirs 目标语言输出目录覆盖表。
     * @param languageNamespaces 目标语言命名空间覆盖表。
     */
    public ProtocolCodegenOptions(
            final List<Path> inputPaths,
            final Path outputDir,
            final String namespace,
            final Path protoIdPath,
            final boolean generateBoImpl,
            final List<CodegenLanguage> languages,
            final Map<CodegenLanguage, Path> languageOutputDirs,
            final Map<CodegenLanguage, String> languageNamespaces) {
        this(inputPaths, outputDir, namespace, protoIdPath, generateBoImpl,
                languages, languageOutputDirs, languageNamespaces, Map.of());
    }

    /**
     * 创建协议代码生成工具参数。
     *
     * @param inputPaths `.si` 文件或目录列表；不允许为空。
     * @param outputDir 默认生成输出目录；不允许为空。
     * @param namespace Java 基础包名；不允许为空。
     * @param protoIdPath protoId 文件路径；可为空。
     * @param generateBoImpl 是否生成 `XXXEventBOImp` 默认实现模板。
     * @param languages 目标语言列表；不允许为空。
     * @param languageOutputDirs 目标语言输出目录覆盖表。
     * @param languageNamespaces 目标语言命名空间覆盖表。
     * @param dtoSuffixes DTO 后缀覆盖表。
     */
    public ProtocolCodegenOptions(
            final List<Path> inputPaths,
            final Path outputDir,
            final String namespace,
            final Path protoIdPath,
            final boolean generateBoImpl,
            final List<CodegenLanguage> languages,
            final Map<CodegenLanguage, Path> languageOutputDirs,
            final Map<CodegenLanguage, String> languageNamespaces,
            final Map<CodegenLanguage, String> dtoSuffixes) {
        this(inputPaths, outputDir, namespace, protoIdPath, generateBoImpl,
                languages, languageOutputDirs, languageNamespaces, dtoSuffixes, Map.of(), Map.of());
    }

    /**
     * 创建协议代码生成工具参数。
     *
     * @throws NullPointerException 当输入列表、输出目录、包名、语言列表或覆盖表为空时抛出。
     * @throws IllegalArgumentException 当输入列表为空、包名空白或语言列表为空时抛出。
     */
    public ProtocolCodegenOptions {
        Objects.requireNonNull(inputPaths, "inputPaths");
        Objects.requireNonNull(outputDir, "outputDir");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(languages, "languages");
        Objects.requireNonNull(languageOutputDirs, "languageOutputDirs");
        Objects.requireNonNull(languageNamespaces, "languageNamespaces");
        Objects.requireNonNull(dtoSuffixes, "dtoSuffixes");
        Objects.requireNonNull(javaArtifactOutputDirs, "javaArtifactOutputDirs");
        Objects.requireNonNull(javaArtifactPackages, "javaArtifactPackages");
        if (inputPaths.isEmpty()) {
            throw new IllegalArgumentException("inputPaths must not be empty");
        }
        if (namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        if (languages.isEmpty()) {
            throw new IllegalArgumentException("languages must not be empty");
        }
        inputPaths = List.copyOf(inputPaths);
        languages = List.copyOf(new LinkedHashSet<>(languages));
        languageOutputDirs = Map.copyOf(new LinkedHashMap<>(languageOutputDirs));
        languageNamespaces = Map.copyOf(new LinkedHashMap<>(languageNamespaces));
        dtoSuffixes = Map.copyOf(new LinkedHashMap<>(dtoSuffixes));
        javaArtifactOutputDirs = Map.copyOf(new LinkedHashMap<>(javaArtifactOutputDirs));
        javaArtifactPackages = Map.copyOf(new LinkedHashMap<>(javaArtifactPackages));
    }

    /**
     * 返回输入路径列表。
     *
     * @return 不可变、有序、不为空、线程安全的输入路径列表。
     */
    @Override
    public List<Path> inputPaths() {
        return inputPaths;
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
}
