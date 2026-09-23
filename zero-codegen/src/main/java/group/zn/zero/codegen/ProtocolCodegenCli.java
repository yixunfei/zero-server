package group.zn.zero.codegen;

import group.zn.zero.codegen.model.CodegenLanguage;
import group.zn.zero.codegen.model.CodegenRequest;
import group.zn.zero.codegen.model.JavaArtifactKind;
import group.zn.zero.core.error.ZeroException;
import java.awt.GraphicsEnvironment;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 协议代码生成工具命令行入口。
 *
 * @author zn
 */
public final class ProtocolCodegenCli {

    /**
     * 默认 Java 协议包名。
     */
    private static final String DEFAULT_PACKAGE = "group.zn.zero.generated";

    /**
     * 默认生成输出目录。
     */
    private static final String DEFAULT_OUTPUT_DIR = "target/generated-sources/zero-codegen";

    /**
     * 支持的命令行参数集合。
     */
    private static final Set<String> KNOWN_OPTIONS = Set.of(
            "--input",
            "--out",
            "--pkg",
            "--protoId",
            "--genBoImpl",
            "--languages",
            "--outJava",
            "--outJavaDto",
            "--outJavaCodec",
            "--outJavaProtocol",
            "--outJavaBo",
            "--outJavaBoImpl",
            "--outJavaDispatcher",
            "--outCs",
            "--outTs",
            "--outGd",
            "--dtoSuffix",
            "--javaDtoSuffix",
            "--csDtoSuffix",
            "--tsDtoSuffix",
            "--gdDtoSuffix",
            "--javaDtoPkg",
            "--javaCodecPkg",
            "--javaProtocolPkg",
            "--javaBoPkg",
            "--javaBoImplPkg",
            "--javaDispatcherPkg",
            "--csNs",
            "--tsNs",
            "--gdNs",
            "--genJava",
            "--genCs",
            "--genTs",
            "--genGd",
            "--gui",
            "--help",
            "-h");

    /**
     * 必须显式携带值的命令行参数集合。
     */
    private static final Set<String> VALUE_OPTIONS = Set.of(
            "--input",
            "--out",
            "--pkg",
            "--protoId",
            "--languages",
            "--outJava",
            "--outJavaDto",
            "--outJavaCodec",
            "--outJavaProtocol",
            "--outJavaBo",
            "--outJavaBoImpl",
            "--outJavaDispatcher",
            "--outCs",
            "--outTs",
            "--outGd",
            "--dtoSuffix",
            "--javaDtoSuffix",
            "--csDtoSuffix",
            "--tsDtoSuffix",
            "--gdDtoSuffix",
            "--javaDtoPkg",
            "--javaCodecPkg",
            "--javaProtocolPkg",
            "--javaBoPkg",
            "--javaBoImplPkg",
            "--javaDispatcherPkg",
            "--csNs",
            "--tsNs",
            "--gdNs");

    /**
     * 命令行使用说明。
     */
    private static final String USAGE = """
            Usage:
              java -jar zero-codegen-<version>-all.jar --gui
              java -jar zero-codegen-<version>-all.jar --input <path[,path...]> [options]

            Options:
              --input <paths>      .si file or directory paths, separated by comma. Required in CLI mode.
              --out <dir>          Java output directory. Default: target/generated-sources/zero-codegen
              --pkg <package>      Java package name. Default: group.zn.zero.generated
              --protoId <file>     protoId.txt path. Optional.
              --genBoImpl <bool>   Create missing XXXEventBOImp templates; preserve existing files. Default: false
              --languages <list>   Target languages: java,csharp,typescript,gdscript. Default: java
              --genJava <bool>     Enable Java generation. Default follows --languages.
              --genCs <bool>       Enable C# generation. Default follows --languages.
              --genTs <bool>       Enable TypeScript generation. Default follows --languages.
              --genGd <bool>       Enable GDScript generation. Default follows --languages.
              --outJava <dir>      Java output directory override.
              --outJavaDto <dir>   Java DTO/enum/marker output directory. Default follows --outJava.
              --outJavaCodec <dir> Java codec output directory. Default follows Java DTO output.
              --outJavaProtocol <dir> Java ProtocolIds output directory. Default follows --outJava.
              --outJavaBo <dir>    Java BO output directory. Default follows --outJava.
              --outJavaBoImpl <dir> Java BOImp output directory. Default follows Java BO output.
              --outJavaDispatcher <dir> Java dispatcher output directory. Default follows Java protocol output.
              --outCs <dir>        C# output directory override.
              --outTs <dir>        TypeScript output directory override.
              --outGd <dir>        GDScript output directory override.
              --dtoSuffix <suffix> Common DTO suffix for generated protocol payload types. Default: DTO
              --javaDtoSuffix <s>  Java DTO suffix override. Empty string disables suffix.
              --csDtoSuffix <s>    C# DTO suffix override. Empty string disables suffix.
              --tsDtoSuffix <s>    TypeScript DTO suffix override. Empty string disables suffix.
              --gdDtoSuffix <s>    GDScript DTO suffix override. Empty string disables suffix.
              --javaDtoPkg <pkg>   Java DTO/enum/marker package. Default: <pkg>.dto
              --javaCodecPkg <pkg> Java codec package. Default: <javaDtoPkg>.codec
              --javaProtocolPkg <pkg> Java protocol package. Default: <pkg>.protocol
              --javaBoPkg <pkg>    Java BO package. Default: <pkg>.bo
              --javaBoImplPkg <pkg> Java BOImp package. Default: <javaBoPkg>.impl
              --javaDispatcherPkg <pkg> Java dispatcher package. Default: <javaProtocolPkg>.dispatch
              --csNs <namespace>   C# namespace. Default follows --pkg.
              --tsNs <namespace>   TypeScript namespace marker. Default follows --pkg.
              --gdNs <namespace>   GDScript namespace marker. Default follows --pkg.
              --gui                Launch Swing GUI mode.
              --help               Print this help.
            """;

    /**
     * 禁止实例化。
     */
    private ProtocolCodegenCli() {
    }

    /**
     * 执行协议代码生成工具。
     *
     * @param args 命令行参数；可为空；无参数且存在图形环境时会进入 GUI 模式。
     */
    public static void main(final String[] args) {
        int exitCode = execute(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * 执行命令行入口并返回退出码。
     *
     * @param args 命令行参数；可为空。
     * @param out 标准输出流；不允许为空。
     * @param err 标准错误流；不允许为空。
     * @return 退出码；`0` 表示成功，非 `0` 表示参数或生成失败；线程安全，方法本身不保留状态。
     */
    static int execute(final String[] args, final PrintStream out, final PrintStream err) {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        try {
            Map<String, String> options = parseArgs(args == null ? new String[0] : args);
            if (options.containsKey("--help") || options.containsKey("-h")) {
                out.print(USAGE);
                return 0;
            }
            if (options.containsKey("--gui") || options.isEmpty()) {
                return launchGui(out, err);
            }
            ProtocolCodegenOptions codegenOptions = toCodegenOptions(options);
            new ProtocolCodegenRunner().run(codegenOptions, out::println);
            return 0;
        } catch (IllegalArgumentException ex) {
            err.println(ex.getMessage());
            err.print(USAGE);
            return 2;
        } catch (ZeroException ex) {
            err.println(ex.code() + ": " + ex.message());
            return 1;
        } catch (RuntimeException ex) {
            err.println("codegen failed: " + ex.getMessage());
            return 1;
        }
    }

    private static int launchGui(final PrintStream out, final PrintStream err) {
        if (GraphicsEnvironment.isHeadless()) {
            err.println("GUI mode requires a graphical desktop environment.");
            out.print(USAGE);
            return 2;
        }
        ProtocolCodegenGui.showWindow();
        return 0;
    }

    private static ProtocolCodegenOptions toCodegenOptions(final Map<String, String> options) {
        String input = required(options, "--input");
        String namespace = options.getOrDefault("--pkg", DEFAULT_PACKAGE);
        Path output = Path.of(options.getOrDefault("--out", DEFAULT_OUTPUT_DIR));
        Path protoId = options.containsKey("--protoId") ? Path.of(options.get("--protoId")) : null;
        boolean generateBoImpl = parseBooleanOption(options, "--genBoImpl", false);
        List<CodegenLanguage> languages = parseLanguages(options);
        Map<CodegenLanguage, Path> outputDirs = parseOutputDirs(options, output);
        Map<CodegenLanguage, String> namespaces = parseNamespaces(options, namespace);
        Map<CodegenLanguage, String> dtoSuffixes = parseDtoSuffixes(options);
        Map<JavaArtifactKind, Path> javaArtifactOutputDirs = parseJavaArtifactOutputDirs(options);
        Map<JavaArtifactKind, String> javaArtifactPackages = parseJavaArtifactPackages(options);
        return new ProtocolCodegenOptions(
                parseInputPaths(input),
                output,
                namespace,
                protoId,
                generateBoImpl,
                languages,
                outputDirs,
                namespaces,
                dtoSuffixes,
                javaArtifactOutputDirs,
                javaArtifactPackages);
    }

    private static Map<String, String> parseArgs(final String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index++) {
            String key = args[index];
            if (!key.startsWith("-")) {
                throw new IllegalArgumentException("unsupported argument: " + key);
            }
            if (!KNOWN_OPTIONS.contains(key)) {
                throw new IllegalArgumentException("unsupported argument: " + key);
            }
            boolean hasValue = index + 1 < args.length && !args[index + 1].startsWith("-");
            if (VALUE_OPTIONS.contains(key)) {
                if (!hasValue) {
                    throw new IllegalArgumentException("missing value for argument: " + key);
                }
                values.put(key, args[++index]);
            } else if (isBooleanOption(key) && hasValue) {
                values.put(key, args[++index]);
            } else {
                if (hasValue) {
                    throw new IllegalArgumentException("argument does not accept value: " + key);
                }
                values.put(key, "true");
            }
        }
        return values;
    }

    private static boolean isBooleanOption(final String key) {
        return "--genBoImpl".equals(key)
                || "--genJava".equals(key)
                || "--genCs".equals(key)
                || "--genTs".equals(key)
                || "--genGd".equals(key);
    }

    private static List<CodegenLanguage> parseLanguages(final Map<String, String> options) {
        LinkedHashSet<CodegenLanguage> languages = new LinkedHashSet<>();
        if (options.containsKey("--languages")) {
            for (String token : options.get("--languages").split(",")) {
                String value = token.trim();
                if (!value.isBlank()) {
                    languages.add(parseLanguage(value));
                }
            }
        } else {
            languages.add(CodegenLanguage.JAVA);
        }
        applyLanguageSwitch(options, "--genJava", CodegenLanguage.JAVA, languages);
        applyLanguageSwitch(options, "--genCs", CodegenLanguage.CSHARP, languages);
        applyLanguageSwitch(options, "--genTs", CodegenLanguage.TYPESCRIPT, languages);
        applyLanguageSwitch(options, "--genGd", CodegenLanguage.GDSCRIPT, languages);
        if (languages.isEmpty()) {
            throw new IllegalArgumentException("at least one language must be enabled");
        }
        return List.copyOf(languages);
    }

    private static void applyLanguageSwitch(
            final Map<String, String> options,
            final String key,
            final CodegenLanguage language,
            final Set<CodegenLanguage> languages) {
        if (!options.containsKey(key)) {
            return;
        }
        if (parseBooleanOption(options, key, false)) {
            languages.add(language);
        } else {
            languages.remove(language);
        }
    }

    private static boolean parseBooleanOption(
            final Map<String, String> options,
            final String key,
            final boolean defaultValue) {
        if (!options.containsKey(key)) {
            return defaultValue;
        }
        String value = options.get(key);
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException("argument must be true or false: " + key);
    }

    private static CodegenLanguage parseLanguage(final String value) {
        return switch (value.trim().toLowerCase()) {
            case "java" -> CodegenLanguage.JAVA;
            case "cs", "csharp", "c#" -> CodegenLanguage.CSHARP;
            case "ts", "typescript" -> CodegenLanguage.TYPESCRIPT;
            case "gd", "gdscript", "godot" -> CodegenLanguage.GDSCRIPT;
            default -> throw new IllegalArgumentException("unsupported language: " + value);
        };
    }

    private static Map<CodegenLanguage, Path> parseOutputDirs(
            final Map<String, String> options,
            final Path defaultOutput) {
        Map<CodegenLanguage, Path> result = new EnumMap<>(CodegenLanguage.class);
        result.put(CodegenLanguage.JAVA, Path.of(options.getOrDefault("--outJava", defaultOutput.toString())));
        if (options.containsKey("--outCs")) {
            result.put(CodegenLanguage.CSHARP, Path.of(options.get("--outCs")));
        }
        if (options.containsKey("--outTs")) {
            result.put(CodegenLanguage.TYPESCRIPT, Path.of(options.get("--outTs")));
        }
        if (options.containsKey("--outGd")) {
            result.put(CodegenLanguage.GDSCRIPT, Path.of(options.get("--outGd")));
        }
        return result;
    }

    private static Map<CodegenLanguage, String> parseNamespaces(
            final Map<String, String> options,
            final String defaultNamespace) {
        Map<CodegenLanguage, String> result = new EnumMap<>(CodegenLanguage.class);
        result.put(CodegenLanguage.JAVA, defaultNamespace);
        result.put(CodegenLanguage.CSHARP, options.getOrDefault("--csNs", toPascalNamespace(defaultNamespace)));
        result.put(CodegenLanguage.TYPESCRIPT, options.getOrDefault("--tsNs", defaultNamespace));
        result.put(CodegenLanguage.GDSCRIPT, options.getOrDefault("--gdNs", defaultNamespace));
        return result;
    }

    private static Map<CodegenLanguage, String> parseDtoSuffixes(final Map<String, String> options) {
        String common = options.getOrDefault("--dtoSuffix", CodegenRequest.DEFAULT_DTO_SUFFIX);
        Map<CodegenLanguage, String> result = new EnumMap<>(CodegenLanguage.class);
        result.put(CodegenLanguage.JAVA, options.getOrDefault("--javaDtoSuffix", common));
        result.put(CodegenLanguage.CSHARP, options.getOrDefault("--csDtoSuffix", common));
        result.put(CodegenLanguage.TYPESCRIPT, options.getOrDefault("--tsDtoSuffix", common));
        result.put(CodegenLanguage.GDSCRIPT, options.getOrDefault("--gdDtoSuffix", common));
        return result;
    }

    private static Map<JavaArtifactKind, Path> parseJavaArtifactOutputDirs(final Map<String, String> options) {
        Map<JavaArtifactKind, Path> result = new EnumMap<>(JavaArtifactKind.class);
        putJavaArtifactOutputDir(options, result, "--outJavaDto", JavaArtifactKind.DTO);
        putJavaArtifactOutputDir(options, result, "--outJavaCodec", JavaArtifactKind.CODEC);
        putJavaArtifactOutputDir(options, result, "--outJavaProtocol", JavaArtifactKind.PROTOCOL);
        putJavaArtifactOutputDir(options, result, "--outJavaBo", JavaArtifactKind.BO);
        putJavaArtifactOutputDir(options, result, "--outJavaBoImpl", JavaArtifactKind.BO_IMPL);
        putJavaArtifactOutputDir(options, result, "--outJavaDispatcher", JavaArtifactKind.DISPATCHER);
        return result;
    }

    private static void putJavaArtifactOutputDir(
            final Map<String, String> options,
            final Map<JavaArtifactKind, Path> result,
            final String option,
            final JavaArtifactKind kind) {
        if (options.containsKey(option)) {
            result.put(kind, Path.of(options.get(option)));
        }
    }

    private static Map<JavaArtifactKind, String> parseJavaArtifactPackages(final Map<String, String> options) {
        Map<JavaArtifactKind, String> result = new EnumMap<>(JavaArtifactKind.class);
        putJavaArtifactPackage(options, result, "--javaDtoPkg", JavaArtifactKind.DTO);
        putJavaArtifactPackage(options, result, "--javaCodecPkg", JavaArtifactKind.CODEC);
        putJavaArtifactPackage(options, result, "--javaProtocolPkg", JavaArtifactKind.PROTOCOL);
        putJavaArtifactPackage(options, result, "--javaBoPkg", JavaArtifactKind.BO);
        putJavaArtifactPackage(options, result, "--javaBoImplPkg", JavaArtifactKind.BO_IMPL);
        putJavaArtifactPackage(options, result, "--javaDispatcherPkg", JavaArtifactKind.DISPATCHER);
        return result;
    }

    private static void putJavaArtifactPackage(
            final Map<String, String> options,
            final Map<JavaArtifactKind, String> result,
            final String option,
            final JavaArtifactKind kind) {
        if (options.containsKey(option)) {
            result.put(kind, options.get(option));
        }
    }

    private static String toPascalNamespace(final String value) {
        StringBuilder builder = new StringBuilder(value.length());
        boolean upperNext = true;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!Character.isLetterOrDigit(current)) {
                upperNext = true;
                if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '.') {
                    builder.append('.');
                }
                continue;
            }
            builder.append(upperNext ? Character.toUpperCase(current) : current);
            upperNext = false;
        }
        return builder.toString();
    }

    private static String required(final Map<String, String> values, final String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        return value;
    }

    private static List<Path> parseInputPaths(final String input) {
        List<Path> paths = new ArrayList<>();
        for (String item : input.split(",")) {
            String value = item.trim();
            if (!value.isBlank()) {
                paths.add(Path.of(value));
            }
        }
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("--input must contain at least one file or directory");
        }
        return paths;
    }
}
