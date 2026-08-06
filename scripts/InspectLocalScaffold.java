import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * zeroServer 生成脚手架结构检查工具。
 *
 * <p>该工具使用 Java 21 source-file 模式运行，用于检查 `NewLocalGame` 生成项目的
 * manifest、README、组件说明、协议文件和本地原型边界。它只做快速结构检查，不替代
 * Maven 测试、批量脚手架验证、external-tests、压测或生产验收。</p>
 *
 * @author zn
 */
public final class InspectLocalScaffold {

    /**
     * 默认检查目录。
     */
    private static final Path DEFAULT_PROJECT_DIR = Path.of(".");

    /**
     * manifest 文件名。
     */
    private static final String MANIFEST_FILE = "zero-scaffold.json";

    /**
     * 支持的本地模板名称。
     */
    private static final List<String> SUPPORTED_TEMPLATES = List.of(
            "local",
            "room",
            "scene-sync",
            "frame-sync",
            "npc-tick",
            "ranking-season",
            "world-shard");

    /**
     * JSON 字符串字段匹配器。
     */
    private static final Pattern STRING_FIELD_PATTERN = Pattern.compile(
            "\"([A-Za-z0-9_]+)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    /**
     * JSON 布尔字段匹配器。
     */
    private static final Pattern BOOLEAN_FIELD_PATTERN = Pattern.compile(
            "\"([A-Za-z0-9_]+)\"\\s*:\\s*(true|false)");

    private InspectLocalScaffold() {
    }

    /**
     * 检查生成脚手架项目。
     *
     * @param args 命令行参数；支持 {@code --projectDir} 和 {@code --help}。
     * @throws IOException 读取项目文件失败时抛出。
     */
    public static void main(final String[] args) throws IOException {
        if (hasFlag(args, "--help", "-h")) {
            printHelp();
            return;
        }
        Options options = Options.parse(args);
        InspectionReport report = inspect(options.projectDir().toAbsolutePath().normalize());
        printReport(report);
        if (report.hasErrors()) {
            System.exit(1);
        }
    }

    private static InspectionReport inspect(final Path projectDir) throws IOException {
        InspectionReport report = new InspectionReport(projectDir);
        if (!Files.isDirectory(projectDir)) {
            report.fail("project-dir", "Project directory not found: " + projectDir);
            return report;
        }
        report.pass("project-dir", projectDir.toString());

        Path pom = projectDir.resolve("pom.xml");
        Path readme = projectDir.resolve("README.md");
        Path businessGuide = projectDir.resolve("BUSINESS_GUIDE.md");
        Path components = projectDir.resolve("COMPONENTS.md");
        Path nextSteps = projectDir.resolve("NEXT_STEPS.md");
        Path manifest = projectDir.resolve(MANIFEST_FILE);
        requireFile(report, "pom", pom);
        requireFile(report, "readme", readme);
        requireFile(report, "business-guide", businessGuide);
        requireFile(report, "components", components);
        requireFile(report, "next-steps", nextSteps);
        requireFile(report, "manifest", manifest);
        if (report.hasErrors()) {
            return report;
        }

        String pomText = Files.readString(pom, StandardCharsets.UTF_8);
        String readmeText = Files.readString(readme, StandardCharsets.UTF_8);
        String businessGuideText = Files.readString(businessGuide, StandardCharsets.UTF_8);
        String componentsText = Files.readString(components, StandardCharsets.UTF_8);
        String nextStepsText = Files.readString(nextSteps, StandardCharsets.UTF_8);
        String manifestText = Files.readString(manifest, StandardCharsets.UTF_8);

        rejectPlaceholder(report, pom, pomText);
        rejectPlaceholder(report, readme, readmeText);
        rejectPlaceholder(report, businessGuide, businessGuideText);
        rejectPlaceholder(report, components, componentsText);
        rejectPlaceholder(report, nextSteps, nextStepsText);
        rejectPlaceholder(report, manifest, manifestText);
        checkPom(report, pom, pomText);
        checkDocs(report, readme, readmeText, businessGuide, businessGuideText, components, componentsText,
                nextSteps, nextStepsText);
        checkManifest(report, projectDir, manifest, manifestText);
        checkSourceLayout(report, projectDir);
        return report;
    }

    private static void requireFile(final InspectionReport report, final String name, final Path file) {
        if (Files.isRegularFile(file)) {
            report.pass(name, file.toString());
        } else {
            report.fail(name, "Missing required file: " + file);
        }
    }

    private static void rejectPlaceholder(final InspectionReport report, final Path file, final String text) {
        if (text.contains("__")) {
            report.fail(file.getFileName().toString(), "Contains unresolved placeholder marker: " + file);
        }
    }

    private static void checkPom(final InspectionReport report, final Path pom, final String text) {
        requireContains(report, "pom-zero-dependency", text, "<groupId>group.zn.zero</groupId>", pom);
        requireContains(report, "pom-starter", text, "<artifactId>zero-server-starter</artifactId>", pom);
        requireContains(report, "pom-codegen", text, "<artifactId>zero-codegen</artifactId>", pom);
        requireContains(report, "pom-generate-sources", text, "<phase>generate-sources</phase>", pom);
        requireContains(report, "pom-protocol-input", text, "src/main/protocol", pom);
    }

    private static void checkDocs(
            final InspectionReport report,
            final Path readme,
            final String readmeText,
            final Path businessGuide,
            final String businessGuideText,
            final Path components,
            final String componentsText,
            final Path nextSteps,
            final String nextStepsText) {
        requireContains(report, "readme-business-guide", readmeText, "BUSINESS_GUIDE.md", readme);
        requireContains(report, "readme-next-business-step", readmeText, "Next Business Step", readme);
        requireContains(report, "readme-first-business-change", readmeText, "First Business Change", readme);
        requireContains(report, "readme-components", readmeText, "COMPONENTS.md", readme);
        requireContains(report, "readme-next-steps", readmeText, "NEXT_STEPS.md", readme);
        requireContains(report, "readme-manifest", readmeText, MANIFEST_FILE, readme);
        requireContains(report, "business-guide-start", businessGuideText, "Start Here", businessGuide);
        requireContains(report, "business-guide-logic", businessGuideText, "Where To Write Business Logic",
                businessGuide);
        requireContains(report, "business-guide-protocol", businessGuideText, "Where To Change Protocol",
                businessGuide);
        requireContains(report, "business-guide-verify", businessGuideText, "Local Verification", businessGuide);
        requireContains(report, "business-guide-first-change", businessGuideText, "First Business Change",
                businessGuide);
        requireContains(report, "business-guide-boundary", businessGuideText, "Production Boundary", businessGuide);
        requireContains(report, "components-template", componentsText, "Template:", components);
        requireContains(report, "components-flow", componentsText, "Component Flow", components);
        requireContains(report, "components-touchpoints", componentsText, "Framework Touchpoints", components);
        requireContains(report, "components-gap", componentsText, "Production Gap", components);
        requireContains(report, "components-business-guide", componentsText, "BUSINESS_GUIDE.md", components);
        requireContains(report, "components-next-steps", componentsText, "NEXT_STEPS.md", components);
        requireContains(report, "components-manifest", componentsText, MANIFEST_FILE, components);
        requireContains(report, "next-steps-gap", nextStepsText, "Production Gap", nextSteps);
        requireContains(report, "next-steps-risk", nextStepsText, "High-Risk Stop Points", nextSteps);
        requireContains(report, "next-steps-checklist", nextStepsText, "Promotion Checklist", nextSteps);
        requireContains(report, "next-steps-runner", nextStepsText, "RunLocalScaffold", nextSteps);
        requireContains(report, "next-steps-design", nextStepsText, "Design Proposal", nextSteps);
    }

    private static void checkManifest(
            final InspectionReport report,
            final Path projectDir,
            final Path manifest,
            final String text) {
        requireContains(report, "manifest-schema", text, "\"schemaVersion\": 1", manifest);
        Map<String, String> strings = parseStringFields(text);
        Map<String, Boolean> booleans = parseBooleanFields(text);

        String projectName = requiredString(report, strings, "projectName", manifest);
        String packageName = requiredString(report, strings, "packageName", manifest);
        String template = requiredString(report, strings, "template", manifest);
        String protocolFile = requiredString(report, strings, "protocolFile", manifest);
        String summaryPrefix = requiredString(report, strings, "summaryPrefix", manifest);
        String productionGap = requiredString(report, strings, "productionGap", manifest);

        report.projectName(projectName);
        report.template(template);
        report.prototype(booleans.getOrDefault("prototype", false));
        if (!projectName.isBlank()) {
            requireContains(report, "pom-project-name", safeRead(projectDir.resolve("pom.xml")), projectName,
                    projectDir.resolve("pom.xml"));
        }
        if (!packageName.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) {
            report.fail("manifest-package", "packageName is not a valid Java package: " + packageName);
        } else {
            report.pass("manifest-package", packageName);
        }
        if (SUPPORTED_TEMPLATES.contains(template)) {
            report.pass("manifest-template", template);
        } else {
            report.fail("manifest-template", "Unsupported template: " + template);
        }
        if (summaryPrefix.isBlank()) {
            report.fail("manifest-summary", "summaryPrefix must not be blank");
        } else {
            report.pass("manifest-summary", summaryPrefix);
        }
        if (productionGap.isBlank()) {
            report.fail("manifest-gap", "productionGap must not be blank");
        } else {
            report.pass("manifest-gap", productionGap);
        }

        Path protocolPath = projectDir.resolve(protocolFile).normalize();
        if (Files.isRegularFile(protocolPath)) {
            report.pass("protocol-file", protocolPath.toString());
            try {
                rejectPlaceholder(report, protocolPath, Files.readString(protocolPath, StandardCharsets.UTF_8));
            } catch (IOException ex) {
                report.fail("protocol-file", "Failed to read protocol file: " + ex.getMessage());
            }
        } else {
            report.fail("protocol-file", "Protocol file not found: " + protocolPath);
        }
        Path protoId = projectDir.resolve("src/main/protocol/protoId.txt");
        if (Files.isRegularFile(protoId)) {
            report.pass("proto-id", protoId.toString());
        } else {
            report.fail("proto-id", "protoId.txt not found: " + protoId);
        }
        checkBoundaryBoolean(report, booleans, "prototype", true);
        checkBoundaryBoolean(report, booleans, "connectsExternalMiddleware", false);
        checkBoundaryBoolean(report, booleans, "opensNetworkPorts", false);
        requireContains(report, "manifest-components", text, "\"zero-codegen\"", manifest);
        requireContains(report, "manifest-business-guide", text, "\"BUSINESS_GUIDE.md\"", manifest);
        requireContains(report, "manifest-documents", text, "\"COMPONENTS.md\"", manifest);
        requireContains(report, "manifest-next-steps", text, "\"NEXT_STEPS.md\"", manifest);
    }

    private static void checkBoundaryBoolean(
            final InspectionReport report,
            final Map<String, Boolean> values,
            final String field,
            final boolean expected) {
        if (!values.containsKey(field)) {
            report.fail("manifest-" + field, "Missing boolean field: " + field);
            return;
        }
        boolean actual = values.get(field);
        if (actual == expected) {
            report.pass("manifest-" + field, field + "=" + actual);
        } else {
            report.fail("manifest-" + field, "Expected " + field + "=" + expected + ", actual=" + actual);
        }
    }

    private static void checkSourceLayout(final InspectionReport report, final Path projectDir) throws IOException {
        Path mainJava = projectDir.resolve("src/main/java");
        Path testJava = projectDir.resolve("src/test/java");
        if (containsJavaFile(mainJava)) {
            report.pass("main-java", mainJava.toString());
        } else {
            report.fail("main-java", "No Java source found under " + mainJava);
        }
        if (containsJavaFile(testJava)) {
            report.pass("test-java", testJava.toString());
        } else {
            report.fail("test-java", "No Java test source found under " + testJava);
        }
    }

    private static boolean containsJavaFile(final Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (var stream = Files.find(root, 16, (path, attributes) ->
                attributes.isRegularFile() && path.getFileName().toString().endsWith(".java"))) {
            return stream.findAny().isPresent();
        }
    }

    private static String requiredString(
            final InspectionReport report,
            final Map<String, String> values,
            final String field,
            final Path file) {
        String value = values.getOrDefault(field, "");
        if (value.isBlank()) {
            report.fail("manifest-" + field, file + " missing string field: " + field);
        } else {
            report.pass("manifest-" + field, value);
        }
        return value;
    }

    private static void requireContains(
            final InspectionReport report,
            final String name,
            final String text,
            final String expected,
            final Path file) {
        if (text.contains(expected)) {
            report.pass(name, expected);
        } else {
            report.fail(name, file + " does not contain expected text: " + expected);
        }
    }

    private static String safeRead(final Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    private static Map<String, String> parseStringFields(final String text) {
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        Matcher matcher = STRING_FIELD_PATTERN.matcher(text);
        while (matcher.find()) {
            result.put(matcher.group(1), unescapeJsonString(matcher.group(2)));
        }
        return result;
    }

    private static Map<String, Boolean> parseBooleanFields(final String text) {
        java.util.LinkedHashMap<String, Boolean> result = new java.util.LinkedHashMap<>();
        Matcher matcher = BOOLEAN_FIELD_PATTERN.matcher(text);
        while (matcher.find()) {
            result.put(matcher.group(1), Boolean.parseBoolean(matcher.group(2)));
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

    private static void printReport(final InspectionReport report) {
        System.out.println("zeroServer local scaffold inspector");
        System.out.println("projectDir: " + report.projectDir());
        for (CheckResult result : report.results()) {
            System.out.println("[" + result.status().label() + "] " + result.name() + " - " + result.message());
        }
        System.out.println();
        if (report.hasErrors()) {
            System.out.println("zero-scaffold-inspector=failed|errors=" + report.errorCount()
                    + "|warnings=" + report.warningCount());
            return;
        }
        System.out.println("zero-scaffold-inspector=ok"
                + "|template=" + report.template()
                + "|project=" + report.projectName()
                + "|prototype=" + report.prototype()
                + "|warnings=" + report.warningCount());
    }

    private static void printHelp() {
        System.out.println("zeroServer generated scaffold inspector");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java scripts/InspectLocalScaffold.java --projectDir target/my-local-game");
        System.out.println("  java scripts/InspectLocalScaffold.java");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --projectDir <path>   Generated scaffold directory. Default: current directory.");
        System.out.println("  --help, -h            Print this help.");
        System.out.println();
        System.out.println("This tool checks structure and metadata only. Run Maven tests separately.");
    }

    /**
     * 命令行参数。
     *
     * @param projectDir 生成脚手架项目目录。
     */
    private record Options(Path projectDir) {

        private static Options parse(final String[] args) {
            Path projectDir = DEFAULT_PROJECT_DIR;
            for (int index = 0; index < args.length; index++) {
                String key = args[index];
                if (!key.startsWith("--")) {
                    throw new IllegalArgumentException("unsupported argument: " + key);
                }
                if (!"--projectDir".equalsIgnoreCase(key) && !"--project-dir".equalsIgnoreCase(key)) {
                    throw new IllegalArgumentException("unsupported argument: " + key);
                }
                if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException("missing value for argument: " + key);
                }
                projectDir = Path.of(args[++index]);
            }
            return new Options(projectDir);
        }
    }

    /**
     * 检查状态。
     */
    private enum CheckStatus {

        /**
         * 检查通过。
         */
        PASS("PASS"),

        /**
         * 检查告警。
         */
        WARN("WARN"),

        /**
         * 检查失败。
         */
        FAIL("FAIL");

        /**
         * 输出标签。
         */
        private final String label;

        CheckStatus(final String label) {
            this.label = label;
        }

        private String label() {
            return label;
        }
    }

    /**
     * 单项检查结果。
     *
     * @param status 检查状态。
     * @param name 检查名称。
     * @param message 检查信息。
     */
    private record CheckResult(CheckStatus status, String name, String message) {
    }

    /**
     * 脚手架检查报告。
     */
    private static final class InspectionReport {

        /**
         * 被检查项目目录。
         */
        private final Path projectDir;

        /**
         * 所有检查结果。
         */
        private final List<CheckResult> results = new ArrayList<>();

        /**
         * manifest 中的模板名称。
         */
        private String template = "unknown";

        /**
         * manifest 中的项目名称。
         */
        private String projectName = "unknown";

        /**
         * manifest 中的原型标记。
         */
        private boolean prototype;

        InspectionReport(final Path projectDir) {
            this.projectDir = projectDir;
        }

        private Path projectDir() {
            return projectDir;
        }

        private void template(final String template) {
            if (!template.isBlank()) {
                this.template = template;
            }
        }

        private String template() {
            return template;
        }

        private void projectName(final String projectName) {
            if (!projectName.isBlank()) {
                this.projectName = projectName;
            }
        }

        private String projectName() {
            return projectName;
        }

        private void prototype(final boolean prototype) {
            this.prototype = prototype;
        }

        private boolean prototype() {
            return prototype;
        }

        private void pass(final String name, final String message) {
            results.add(new CheckResult(CheckStatus.PASS, name, message));
        }

        private void fail(final String name, final String message) {
            results.add(new CheckResult(CheckStatus.FAIL, name, message));
        }

        private List<CheckResult> results() {
            return List.copyOf(results);
        }

        private boolean hasErrors() {
            return results.stream().anyMatch(result -> result.status() == CheckStatus.FAIL);
        }

        private long errorCount() {
            return results.stream().filter(result -> result.status() == CheckStatus.FAIL).count();
        }

        private long warningCount() {
            return results.stream().filter(result -> result.status() == CheckStatus.WARN).count();
        }
    }
}
