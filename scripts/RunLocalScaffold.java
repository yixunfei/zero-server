import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * zeroServer 单个本地脚手架 smoke runner。
 *
 * <p>该工具使用 Java 21 source-file 模式运行，面向 `NewLocalGame` 生成项目执行一键
 * local/prototype smoke：先调用 `InspectLocalScaffold` 检查结构，再执行 Maven test 和
 * Maven run，并校验运行输出包含 manifest 中的 `summaryPrefix`。它不替代 external-tests、
 * 压测、长稳或生产验收。</p>
 *
 * @author zn
 */
public final class RunLocalScaffold {

    /**
     * 默认项目目录。
     */
    private static final Path DEFAULT_PROJECT_DIR = Path.of(".");

    /**
     * manifest 文件名。
     */
    private static final String MANIFEST_FILE = "zero-scaffold.json";

    /**
     * JSON 字符串字段匹配器。
     */
    private static final Pattern STRING_FIELD_PATTERN = Pattern.compile(
            "\"([A-Za-z0-9_]+)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private RunLocalScaffold() {
    }

    /**
     * 执行单项目脚手架 smoke。
     *
     * @param args 命令行参数；支持 {@code --projectDir}、{@code --skipTests}、{@code --skipRun} 和 {@code --help}。
     * @throws IOException 子进程启动或 manifest 读取失败时抛出。
     * @throws InterruptedException 等待子进程时被中断。
     */
    public static void main(final String[] args) throws IOException, InterruptedException {
        if (hasFlag(args, "--help", "-h")) {
            printHelp();
            return;
        }
        Options options = Options.parse(args);
        Path projectDir = options.projectDir().toAbsolutePath().normalize();
        Manifest manifest = Manifest.read(projectDir.resolve(MANIFEST_FILE));

        System.out.println("Running zeroServer local scaffold smoke:");
        System.out.println("  projectDir: " + projectDir);
        System.out.println("  template: " + manifest.template());
        System.out.println("  project: " + manifest.projectName());
        System.out.println("  summaryPrefix: " + manifest.summaryPrefix());

        CommandResult inspectResult = runCommand(List.of(
                javaCommand(),
                "scripts/InspectLocalScaffold.java",
                "--projectDir",
                projectDir.toString()));
        requireOutput(inspectResult.output(), "zero-scaffold-inspector=ok", "inspector summary");

        boolean tested = false;
        boolean ran = false;
        if (!options.skipTests()) {
            runCommand(List.of(mavenCommand(), "-q", "-f", projectDir.resolve("pom.xml").toString(), "clean", "test"));
            tested = true;
        }

        if (!options.skipRun()) {
            CommandResult runResult = runCommand(List.of(
                    mavenCommand(),
                    "-q",
                    "-f",
                    projectDir.resolve("pom.xml").toString(),
                    "exec:java"));
            requireOutput(runResult.output(), manifest.summaryPrefix(), "run summary prefix");
            ran = true;
        }

        System.out.println("zero-scaffold-runner=ok"
                + "|template=" + manifest.template()
                + "|project=" + manifest.projectName()
                + "|inspected=true"
                + "|tested=" + tested
                + "|ran=" + ran
                + "|summaryPrefix=" + manifest.summaryPrefix());
    }

    private static void requireOutput(final String output, final String expected, final String label) {
        if (!output.contains(expected)) {
            throw new IllegalStateException("Expected " + label + " not found: " + expected
                    + System.lineSeparator() + tail(output));
        }
    }

    private static CommandResult runCommand(final List<String> command) throws IOException, InterruptedException {
        System.out.println("$ " + String.join(" ", command));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
                System.out.println(line);
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Command failed with exit code " + exitCode
                    + System.lineSeparator() + String.join(" ", command)
                    + System.lineSeparator() + tail(output.toString()));
        }
        return new CommandResult(exitCode, output.toString());
    }

    private static String javaCommand() {
        String binary = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", binary).toString();
    }

    private static String mavenCommand() {
        return isWindows() ? "mvn.cmd" : "mvn";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String tail(final String value) {
        int maxLength = 4_000;
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(value.length() - maxLength);
    }

    private static boolean hasFlag(final String[] args, final String... flags) {
        for (String arg : args) {
            for (String flag : flags) {
                if (flag.equalsIgnoreCase(arg)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, String> parseStringFields(final String text) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = STRING_FIELD_PATTERN.matcher(text);
        while (matcher.find()) {
            result.put(matcher.group(1), unescapeJsonString(matcher.group(2)));
        }
        return result;
    }

    private static String unescapeJsonString(final String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\\' || index + 1 >= value.length()) {
                builder.append(current);
                continue;
            }
            char escaped = value.charAt(++index);
            switch (escaped) {
                case '"' -> builder.append('"');
                case '\\' -> builder.append('\\');
                case '/' -> builder.append('/');
                case 'b' -> builder.append('\b');
                case 'f' -> builder.append('\f');
                case 'n' -> builder.append('\n');
                case 'r' -> builder.append('\r');
                case 't' -> builder.append('\t');
                case 'u' -> {
                    if (index + 4 >= value.length()) {
                        builder.append("\\u");
                    } else {
                        String hex = value.substring(index + 1, index + 5);
                        try {
                            builder.append((char) Integer.parseInt(hex, 16));
                            index += 4;
                        } catch (NumberFormatException ex) {
                            builder.append("\\u").append(hex);
                            index += 4;
                        }
                    }
                }
                default -> builder.append(escaped);
            }
        }
        return builder.toString();
    }

    private static void printHelp() {
        System.out.println("zeroServer local scaffold smoke runner");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java scripts/RunLocalScaffold.java --projectDir target/my-local-game");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --projectDir <path>   Generated scaffold directory. Default: current directory.");
        System.out.println("  --skipTests           Skip Maven clean test.");
        System.out.println("  --skipRun             Skip Maven exec:java.");
        System.out.println("  --help, -h            Print this help.");
        System.out.println();
        System.out.println("Before running:");
        System.out.println("  mvn -q -DskipTests install");
        System.out.println();
        System.out.println("This tool is for local/prototype smoke only, not production readiness.");
    }

    /**
     * 命令行参数。
     *
     * @param projectDir 生成脚手架项目目录。
     * @param skipTests 是否跳过 Maven 测试。
     * @param skipRun 是否跳过 Maven 运行。
     */
    private record Options(Path projectDir, boolean skipTests, boolean skipRun) {

        private static Options parse(final String[] args) {
            Path projectDir = DEFAULT_PROJECT_DIR;
            boolean skipTests = false;
            boolean skipRun = false;
            List<String> values = new ArrayList<>(List.of(args));
            for (int index = 0; index < values.size(); index++) {
                String key = values.get(index);
                if ("--skipTests".equalsIgnoreCase(key) || "--skip-tests".equalsIgnoreCase(key)) {
                    skipTests = true;
                    continue;
                }
                if ("--skipRun".equalsIgnoreCase(key) || "--skip-run".equalsIgnoreCase(key)) {
                    skipRun = true;
                    continue;
                }
                if (!"--projectDir".equalsIgnoreCase(key) && !"--project-dir".equalsIgnoreCase(key)) {
                    throw new IllegalArgumentException("unsupported argument: " + key);
                }
                if (index + 1 >= values.size() || values.get(index + 1).startsWith("--")) {
                    throw new IllegalArgumentException("missing value for argument: " + key);
                }
                projectDir = Path.of(values.get(++index));
            }
            return new Options(projectDir, skipTests, skipRun);
        }
    }

    /**
     * 脚手架 manifest 摘要。
     *
     * @param projectName 项目名。
     * @param template 模板名。
     * @param summaryPrefix 运行摘要前缀。
     */
    private record Manifest(String projectName, String template, String summaryPrefix) {

        private static Manifest read(final Path manifest) throws IOException {
            if (!Files.isRegularFile(manifest)) {
                throw new IllegalStateException("zero-scaffold.json not found: " + manifest);
            }
            Map<String, String> fields = parseStringFields(Files.readString(manifest, StandardCharsets.UTF_8));
            String projectName = required(fields, "projectName", manifest);
            String template = required(fields, "template", manifest);
            String summaryPrefix = required(fields, "summaryPrefix", manifest);
            return new Manifest(projectName, template, summaryPrefix);
        }

        private static String required(final Map<String, String> fields, final String name, final Path manifest) {
            String value = fields.getOrDefault(name, "");
            if (value.isBlank()) {
                throw new IllegalStateException(manifest + " missing required string field: " + name);
            }
            return value;
        }
    }

    /**
     * 子进程执行结果。
     *
     * @param exitCode 退出码。
     * @param output 标准输出和错误输出的合并文本。
     */
    private record CommandResult(int exitCode, String output) {
    }
}
