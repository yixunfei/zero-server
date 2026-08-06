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
import java.util.Objects;

/**
 * zeroServer 本地脚手架批量验证工具。
 *
 * <p>该工具使用 Java 21 source-file 模式运行，按固定矩阵生成并验证 local、room、
 * scene-sync、frame-sync、npc-tick、ranking-season、world-shard 七种 local/prototype 项目。执行前需要先在仓库根目录运行
 * {@code mvn -q -DskipTests install}，让生成项目可以解析当前 SNAPSHOT 依赖。</p>
 *
 * @author zn
 */
public final class VerifyLocalScaffolds {

    /**
     * 默认输出目录。
     */
    private static final Path DEFAULT_OUTPUT_DIR = Path.of("target", "scaffold-verify");

    /**
     * 默认 zeroServer 版本。
     */
    private static final String DEFAULT_ZERO_VERSION = "0.1.0-SNAPSHOT";

    /**
     * 脚手架验证矩阵。
     */
    private static final List<TemplateSpec> TEMPLATE_SPECS = List.of(
            new TemplateSpec(
                    "local",
                    "verify-local-game",
                    "group.zn.zero.generated.verify.localgame",
                    "src/main/protocol/Game.si",
                    "local-game=ok|mode=local|name=verify-local-game|uid=1001|position=3,5"
                            + "|logs=4|metrics=2|maxProtocolId=90105"),
            new TemplateSpec(
                    "room",
                    "verify-room-game",
                    "group.zn.zero.generated.verify.roomgame",
                    "src/main/protocol/Room.si",
                    "room-game=ok|mode=local|name=verify-room-game"
                            + "|summary=room=room-1,owner=1001,players=2,ready=2,started=true,frame=1,inputSum=42"
                            + "|logs=7|metrics=6|maxProtocolId=91109"),
            new TemplateSpec(
                    "scene-sync",
                    "verify-scene-sync",
                    "group.zn.zero.generated.verify.scenesync",
                    "src/main/protocol/SceneSync.si",
                    "scene-sync=ok|mode=local|name=verify-scene-sync"
                            + "|summary=scene=scene-1,entities=3,focus=1001,visible=2,"
                            + "visibleIds=1002,1003,lastDelta=move:1003@8,8"
                            + "|logs=6|metrics=5|maxProtocolId=92105"),
            new TemplateSpec(
                    "frame-sync",
                    "verify-frame-sync",
                    "group.zn.zero.generated.verify.framesync",
                    "src/main/protocol/FrameSync.si",
                    "frame-sync=ok|mode=local|name=verify-frame-sync"
                            + "|summary=match=match-1,players=2,frame=2,inputs=3,inputSum=17,snapshots=2"
                            + "|logs=9|metrics=8|maxProtocolId=93107"),
            new TemplateSpec(
                    "npc-tick",
                    "verify-npc-tick",
                    "group.zn.zero.generated.verify.npctick",
                    "src/main/protocol/NpcTick.si",
                    "npc-tick=ok|mode=local|name=verify-npc-tick"
                            + "|summary=zone=zone-1,npcs=1,npc=2001,behavior=idle,position=12,10,"
                            + "tick=3,updates=7,lastAction=query:2001@12,10"
                            + "|logs=8|metrics=7|maxProtocolId=94107"),
            new TemplateSpec(
                    "ranking-season",
                    "verify-ranking-season",
                    "group.zn.zero.generated.verify.rankingseason",
                    "src/main/protocol/RankingSeason.si",
                    "ranking-season=ok|mode=local|name=verify-ranking-season"
                            + "|summary=season=season-2,players=1,top=1001:300,player=1001,"
                            + "rank=1,score=300,resets=1,lastAction=rank:1001=1"
                            + "|logs=9|metrics=8|maxProtocolId=95107"),
            new TemplateSpec(
                    "world-shard",
                    "verify-world-shard",
                    "group.zn.zero.generated.verify.worldshard",
                    "src/main/protocol/WorldShard.si",
                    "world-shard=ok|mode=local|name=verify-world-shard"
                            + "|summary=world=world-1,shards=2,entities=2,entity=1001,shard=shard-b,"
                            + "position=54,4,migrations=1,lastAction=query:1001@shard-b"
                            + "|logs=7|metrics=6|maxProtocolId=96107"));

    private VerifyLocalScaffolds() {
    }

    /**
     * 批量验证本地脚手架模板。
     *
     * @param args 命令行参数；支持 {@code --outputDir}、{@code --zeroVersion} 和 {@code --help}。
     * @throws IOException 子进程启动或输出读取失败时抛出。
     * @throws InterruptedException 当前线程等待子进程时被中断。
     */
    public static void main(final String[] args) throws IOException, InterruptedException {
        if (hasFlag(args, "--help", "-h")) {
            printHelp();
            return;
        }
        Options options = Options.parse(args);
        Path outputRoot = options.outputDir().toAbsolutePath().normalize();

        System.out.println("Verifying zeroServer local scaffolds:");
        System.out.println("  outputDir: " + outputRoot);
        System.out.println("  zeroVersion: " + options.zeroVersion());

        for (TemplateSpec spec : TEMPLATE_SPECS) {
            verifyTemplate(spec, outputRoot, options.zeroVersion());
        }

        System.out.println("All local scaffolds verified: " + TEMPLATE_SPECS.size());
    }

    private static void verifyTemplate(
            final TemplateSpec spec,
            final Path outputRoot,
            final String zeroVersion) throws IOException, InterruptedException {
        Path projectDir = outputRoot.resolve(spec.projectName()).normalize();
        System.out.println();
        System.out.println("==> " + spec.template() + " -> " + projectDir);

        List<String> generateCommand = new ArrayList<>();
        generateCommand.add(javaCommand());
        generateCommand.add("scripts/NewLocalGame.java");
        if (!"local".equals(spec.template())) {
            generateCommand.add("--template");
            generateCommand.add(spec.template());
        }
        generateCommand.add("--projectName");
        generateCommand.add(spec.projectName());
        generateCommand.add("--packageName");
        generateCommand.add(spec.packageName());
        generateCommand.add("--outputDir");
        generateCommand.add(projectDir.toString());
        generateCommand.add("--zeroVersion");
        generateCommand.add(zeroVersion);
        generateCommand.add("--force");
        runCommand(generateCommand);
        verifyGeneratedDocumentation(spec, projectDir);
        CommandResult smokeResult = runCommand(List.of(
                javaCommand(),
                "scripts/RunLocalScaffold.java",
                "--projectDir",
                projectDir.toString()));
        String expectedSmokeSummary = "zero-scaffold-runner=ok|template=" + spec.template()
                + "|project=" + spec.projectName()
                + "|inspected=true|tested=true|ran=true";
        if (!smokeResult.output().contains(expectedSmokeSummary)) {
            throw new IllegalStateException("Smoke runner summary not found for template " + spec.template()
                    + System.lineSeparator() + "expected: " + expectedSmokeSummary
                    + System.lineSeparator() + "actual tail: " + tail(smokeResult.output()));
        }
        if (!smokeResult.output().contains(spec.expectedSummary())) {
            throw new IllegalStateException("Expected summary not found for template " + spec.template()
                    + System.lineSeparator() + "expected: " + spec.expectedSummary()
                    + System.lineSeparator() + "actual tail: " + tail(smokeResult.output()));
        }
        System.out.println("Verified " + spec.template() + ": " + spec.expectedSummary());
    }

    private static void verifyGeneratedDocumentation(final TemplateSpec spec, final Path projectDir)
            throws IOException {
        Path components = projectDir.resolve("COMPONENTS.md");
        Path nextSteps = projectDir.resolve("NEXT_STEPS.md");
        Path readme = projectDir.resolve("README.md");
        Path businessGuide = projectDir.resolve("BUSINESS_GUIDE.md");
        Path manifest = projectDir.resolve("zero-scaffold.json");
        if (!Files.isRegularFile(components)) {
            throw new IllegalStateException("COMPONENTS.md not found for template " + spec.template());
        }
        if (!Files.isRegularFile(businessGuide)) {
            throw new IllegalStateException("BUSINESS_GUIDE.md not found for template " + spec.template());
        }
        if (!Files.isRegularFile(nextSteps)) {
            throw new IllegalStateException("NEXT_STEPS.md not found for template " + spec.template());
        }
        if (!Files.isRegularFile(readme)) {
            throw new IllegalStateException("README.md not found for template " + spec.template());
        }
        if (!Files.isRegularFile(manifest)) {
            throw new IllegalStateException("zero-scaffold.json not found for template " + spec.template());
        }
        String componentsText = Files.readString(components, StandardCharsets.UTF_8);
        String businessGuideText = Files.readString(businessGuide, StandardCharsets.UTF_8);
        String nextStepsText = Files.readString(nextSteps, StandardCharsets.UTF_8);
        String readmeText = Files.readString(readme, StandardCharsets.UTF_8);
        String manifestText = Files.readString(manifest, StandardCharsets.UTF_8);
        requireContains(businessGuideText, "Template: `" + spec.template() + "`", businessGuide);
        requireContains(businessGuideText, "Protocol file: `" + spec.protocolFile() + "`", businessGuide);
        requireContains(businessGuideText, "Expected run summary: `" + spec.summaryPrefix() + "`", businessGuide);
        requireContains(businessGuideText, "Where To Write Business Logic", businessGuide);
        requireContains(businessGuideText, "Where To Change Protocol", businessGuide);
        requireContains(businessGuideText, "Local Verification", businessGuide);
        requireContains(businessGuideText, "First Business Change", businessGuide);
        requireContains(businessGuideText, "Production Boundary", businessGuide);
        requireContains(componentsText, "Template: `" + spec.template() + "`", components);
        requireContains(componentsText, "Protocol file: `" + spec.protocolFile() + "`", components);
        requireContains(componentsText, "Expected summary prefix: `" + spec.summaryPrefix() + "`", components);
        requireContains(componentsText, "Framework Touchpoints", components);
        requireContains(componentsText, "Production Gap", components);
        requireContains(componentsText, "BUSINESS_GUIDE.md", components);
        requireContains(componentsText, "NEXT_STEPS.md", components);
        requireContains(componentsText, "zero-scaffold.json", components);
        requireContains(nextStepsText, "Template: `" + spec.template() + "`", nextSteps);
        requireContains(nextStepsText, "Protocol file: `" + spec.protocolFile() + "`", nextSteps);
        requireContains(nextStepsText, "Local smoke summary: `" + spec.summaryPrefix() + "`", nextSteps);
        requireContains(nextStepsText, "High-Risk Stop Points", nextSteps);
        requireContains(nextStepsText, "Promotion Checklist", nextSteps);
        requireContains(nextStepsText, "Design Proposal", nextSteps);
        requireContains(readmeText, "BUSINESS_GUIDE.md", readme);
        requireContains(readmeText, "Next Business Step", readme);
        requireContains(readmeText, "First Business Change", readme);
        requireContains(readmeText, "COMPONENTS.md", readme);
        requireContains(readmeText, "NEXT_STEPS.md", readme);
        requireContains(readmeText, "zero-scaffold.json", readme);
        requireContains(manifestText, "\"schemaVersion\": 1", manifest);
        requireContains(manifestText, "\"projectName\": \"" + spec.projectName() + "\"", manifest);
        requireContains(manifestText, "\"packageName\": \"" + spec.packageName() + "\"", manifest);
        requireContains(manifestText, "\"template\": \"" + spec.template() + "\"", manifest);
        requireContains(manifestText, "\"protocolFile\": \"" + spec.protocolFile() + "\"", manifest);
        requireContains(manifestText, "\"summaryPrefix\": \"" + spec.summaryPrefix() + "\"", manifest);
        requireContains(manifestText, "\"prototype\": true", manifest);
        requireContains(manifestText, "\"zero-codegen\"", manifest);
        requireContains(manifestText, "\"BUSINESS_GUIDE.md\"", manifest);
        requireContains(manifestText, "\"COMPONENTS.md\"", manifest);
        requireContains(manifestText, "\"NEXT_STEPS.md\"", manifest);
        requireContains(manifestText, "\"productionGap\":", manifest);
        rejectUnresolvedPlaceholder(businessGuideText, businessGuide);
        rejectUnresolvedPlaceholder(componentsText, components);
        rejectUnresolvedPlaceholder(nextStepsText, nextSteps);
        rejectUnresolvedPlaceholder(readmeText, readme);
        rejectUnresolvedPlaceholder(manifestText, manifest);
        System.out.println("Verified business guide for " + spec.template() + ": " + businessGuide);
        System.out.println("Verified component docs for " + spec.template() + ": " + components);
        System.out.println("Verified scaffold manifest for " + spec.template() + ": " + manifest);
    }

    private static void requireContains(final String text, final String expected, final Path file) {
        if (!text.contains(expected)) {
            throw new IllegalStateException(file + " does not contain expected text: " + expected);
        }
    }

    private static void rejectUnresolvedPlaceholder(final String text, final Path file) {
        if (text.contains("__")) {
            throw new IllegalStateException(file + " contains unresolved placeholder marker");
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
        Path javaPath = Path.of(System.getProperty("java.home"), "bin", binary);
        return javaPath.toString();
    }

    private static String mavenCommand() {
        return isWindows() ? "mvn.cmd" : "mvn";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
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

    private static String tail(final String value) {
        int maxLength = 4_000;
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(value.length() - maxLength);
    }

    private static void printHelp() {
        System.out.println("zeroServer local scaffold verifier");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java scripts/VerifyLocalScaffolds.java [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --outputDir <path>        Verification output directory. Default: "
                + DEFAULT_OUTPUT_DIR);
        System.out.println("  --zeroVersion <version>   zeroServer dependency version. Default: "
                + DEFAULT_ZERO_VERSION);
        System.out.println("  --help, -h                Print this help");
        System.out.println();
        System.out.println("Before running:");
        System.out.println("  mvn -q -DskipTests install");
    }

    /**
     * 命令行参数。
     *
     * @param outputDir 输出目录。
     * @param zeroVersion zeroServer 依赖版本。
     */
    private record Options(Path outputDir, String zeroVersion) {

        private static Options parse(final String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < args.length; index++) {
                String key = args[index];
                if (!key.startsWith("--")) {
                    throw new IllegalArgumentException("unsupported argument: " + key);
                }
                if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException("missing value for argument: " + key);
                }
                values.put(key.toLowerCase(Locale.ROOT), args[++index]);
            }
            Path outputDir = Path.of(values.getOrDefault("--outputdir", DEFAULT_OUTPUT_DIR.toString()));
            String zeroVersion = values.getOrDefault("--zeroversion", DEFAULT_ZERO_VERSION);
            if (zeroVersion.isBlank()) {
                throw new IllegalArgumentException("zeroVersion must not be blank");
            }
            return new Options(outputDir, zeroVersion);
        }
    }

    /**
     * 单个模板验证规格。
     *
     * @param template 模板名称。
     * @param projectName 生成项目名。
     * @param packageName 生成项目 Java 包名。
     * @param protocolFile 生成协议文件路径。
     * @param expectedSummary 期望运行摘要。
     */
    private record TemplateSpec(
            String template,
            String projectName,
            String packageName,
            String protocolFile,
            String expectedSummary) {

        private TemplateSpec {
            Objects.requireNonNull(template, "template");
            Objects.requireNonNull(projectName, "projectName");
            Objects.requireNonNull(packageName, "packageName");
            Objects.requireNonNull(protocolFile, "protocolFile");
            Objects.requireNonNull(expectedSummary, "expectedSummary");
        }

        private String summaryPrefix() {
            int separator = expectedSummary.indexOf('|');
            return separator < 0 ? expectedSummary : expectedSummary.substring(0, separator);
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
