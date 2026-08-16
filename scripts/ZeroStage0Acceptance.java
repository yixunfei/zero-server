import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * zeroServer 阶段 0 本地开箱即用统一验收入口。
 *
 * <p>该工具只编排仓库已经公开的 Doctor、架构守卫、Maven 门禁、示例和脚手架工具。
 * {@code quick} 验证首次上手关键路径，{@code full} 增加质量、集成、全部示例和七类脚手架。
 * 所有生成物和日志都被限制在仓库 {@code target/} 子目录，不连接外部中间件，也不证明生产就绪。</p>
 *
 * @author zn
 */
public final class ZeroStage0Acceptance {

    /** 默认验收输出目录。 */
    private static final Path DEFAULT_OUTPUT_DIR = Path.of("target", "stage0-acceptance");

    /** 单项检查默认超时。 */
    private static final Duration DEFAULT_CHECK_TIMEOUT = Duration.ofMinutes(10);

    /** 单项检查最短允许超时。 */
    private static final long MINIMUM_TIMEOUT_SECONDS = 30L;

    /** 单项检查最长允许超时。 */
    private static final long MAXIMUM_TIMEOUT_SECONDS = 3_600L;

    /** 失败进程优雅终止等待时间。 */
    private static final Duration PROCESS_STOP_GRACE = Duration.ofSeconds(2);

    /** 禁止实例化。 */
    private ZeroStage0Acceptance() {
    }

    /**
     * 执行阶段 0 验收。
     *
     * @param args 支持 level、输出目录、单项超时、plan 和 help；数组不会被修改。
     * @implNote 命令按确定顺序串行执行；仅输出读取使用每个子进程一个短生命周期虚拟线程。
     */
    public static void main(final String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException exception) {
            System.err.println("zero-stage0-acceptance=invalid|reason=" + safeMessage(exception));
            printHelp();
            System.exit(2);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.err.println("zero-stage0-acceptance=interrupted");
            System.exit(130);
        } catch (IOException exception) {
            System.err.println("zero-stage0-acceptance=failed|reason=" + safeMessage(exception));
            System.exit(1);
        }
    }

    /**
     * 解析参数、规划并执行验收。
     *
     * @param args 命令行参数；不可为空。
     * @throws IOException 创建日志或启动子进程失败时抛出。
     * @throws InterruptedException 等待子进程时被中断。
     */
    private static void run(final String[] args) throws IOException, InterruptedException {
        if (hasFlag(args, "--help", "-h")) {
            printHelp();
            return;
        }
        Options options = Options.parse(args);
        Path repositoryRoot = requireRepositoryRoot();
        Path outputDirectory = resolveOutputDirectory(repositoryRoot, options.outputDirectory());
        List<CheckSpec> checks = createChecks(options.level(), outputDirectory);
        if (options.planOnly()) {
            printPlan(options, repositoryRoot, outputDirectory, checks);
            return;
        }

        Files.createDirectories(outputDirectory.resolve("logs"));
        long startedAt = System.nanoTime();
        List<CheckResult> results = executeChecks(
                repositoryRoot, outputDirectory, options.checkTimeout(), checks);
        printSummary(options.level(), repositoryRoot, outputDirectory, startedAt, checks, results);
        if (results.stream().anyMatch(result -> !result.passed())) {
            System.exit(1);
        }
    }

    /**
     * 按确定顺序执行检查，并在首个失败后停止依赖链。
     *
     * @param repositoryRoot 仓库根目录；不可为空。
     * @param outputDirectory target 下验收输出目录；不可为空。
     * @param timeout 单项检查超时；不可为空。
     * @param checks 有序、不可变检查清单；不可为空。
     * @return 有序、不可变结果；非空，线程安全。
     * @throws IOException 日志创建或进程启动失败时抛出。
     * @throws InterruptedException 等待子进程时被中断。
     */
    private static List<CheckResult> executeChecks(
            final Path repositoryRoot,
            final Path outputDirectory,
            final Duration timeout,
            final List<CheckSpec> checks) throws IOException, InterruptedException {
        List<CheckResult> results = new ArrayList<>();
        for (CheckSpec check : checks) {
            CheckResult result = executeCheck(repositoryRoot, outputDirectory, timeout, check);
            results.add(result);
            printCheckResult(repositoryRoot, result);
            if (!result.passed()) {
                break;
            }
        }
        return List.copyOf(results);
    }

    /**
     * 执行一个子进程检查并保存完整日志。
     *
     * @param repositoryRoot 仓库根目录；不可为空。
     * @param outputDirectory target 下验收输出目录；不可为空。
     * @param timeout 单项超时；不可为空。
     * @param check 检查规格；不可为空。
     * @return 不可变执行结果；不会为空。
     * @throws IOException 日志创建或进程启动失败时抛出。
     * @throws InterruptedException 等待进程或日志线程时被中断。
     */
    private static CheckResult executeCheck(
            final Path repositoryRoot,
            final Path outputDirectory,
            final Duration timeout,
            final CheckSpec check) throws IOException, InterruptedException {
        Path logFile = outputDirectory.resolve("logs").resolve(check.id() + ".log");
        System.out.println();
        System.out.println("==> " + check.id() + " - " + check.description());
        System.out.println("$ " + String.join(" ", check.command()));
        Process process = new ProcessBuilder(check.command())
                .directory(repositoryRoot.toFile())
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        AtomicReference<IOException> outputFailure = new AtomicReference<>();
        Thread outputThread = startOutputReader(process, logFile, output, outputFailure, check.id());
        long startedAt = System.nanoTime();
        boolean completed = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
        if (!completed) {
            terminateProcessTree(process);
        }
        outputThread.join();
        if (outputFailure.get() != null) {
            throw outputFailure.get();
        }
        long durationMillis = elapsedMillis(startedAt);
        int exitCode = completed ? process.exitValue() : 124;
        boolean markerFound = check.expectedMarker().isBlank()
                || output.indexOf(check.expectedMarker()) >= 0;
        boolean passed = completed && exitCode == 0 && markerFound;
        String detail = resultDetail(completed, exitCode, markerFound, check.expectedMarker());
        return new CheckResult(check.id(), passed, durationMillis, logFile, detail);
    }

    /**
     * 启动子进程输出读取虚拟线程。
     *
     * @param process 已启动子进程；不可为空。
     * @param logFile 输出日志文件；不可为空。
     * @param output 当前检查的可变输出缓冲；仅新线程写入。
     * @param outputFailure 输出读取错误槽；仅新线程写入。
     * @param checkId 检查 ID，用于线程命名；不可为空。
     * @return 已启动的虚拟线程；不会为空。
     */
    private static Thread startOutputReader(
            final Process process,
            final Path logFile,
            final StringBuilder output,
            final AtomicReference<IOException> outputFailure,
            final String checkId) {
        return Thread.ofVirtual().name("zero-stage0-output-" + checkId).start(() -> {
            try (BufferedReader reader = process.inputReader(processOutputCharset());
                    BufferedWriter writer = Files.newBufferedWriter(
                            logFile,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                    writer.write(line);
                    writer.newLine();
                    System.out.println(line);
                }
            } catch (IOException exception) {
                outputFailure.set(exception);
            }
        });
    }

    /**
     * 精确终止当前验收启动的超时进程树。
     *
     * @param process 当前检查的子进程；不可为空。
     * @throws InterruptedException 等待优雅终止时被中断。
     */
    private static void terminateProcessTree(final Process process) throws InterruptedException {
        List<ProcessHandle> descendants = process.descendants().toList().reversed();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        if (!process.waitFor(PROCESS_STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
            descendants.forEach(handle -> {
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            });
            process.destroyForcibly();
            process.waitFor(PROCESS_STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 创建 quick/full 检查清单。
     *
     * @param level 验收级别；不可为空。
     * @param outputDirectory target 下输出目录；不可为空。
     * @return 有序、不可变且非空的检查清单。
     */
    private static List<CheckSpec> createChecks(
            final AcceptanceLevel level,
            final Path outputDirectory) {
        List<CheckSpec> checks = new ArrayList<>();
        checks.add(javaCheck("doctor", "环境和公开入口", "scripts/ZeroLocalDoctor.java", "zero-local-doctor=ok"));
        checks.add(javaCheck(
                "architecture", "模块依赖边界", "scripts/ZeroArchitectureGuard.java", "zero-architecture-guard=ok"));
        checks.add(mavenCheck("unit-tests", "默认单元测试", List.of("test")));
        if (level == AcceptanceLevel.FULL) {
            addFullBuildChecks(checks);
        }
        checks.add(mavenCheck("install", "安装当前 SNAPSHOT", List.of("-DskipTests", "install")));
        if (level == AcceptanceLevel.FULL) {
            addExampleChecks(checks);
        }
        checks.add(prototypeCheck(outputDirectory));
        if (level == AcceptanceLevel.FULL) {
            checks.add(scaffoldCheck(outputDirectory));
        }
        return List.copyOf(checks);
    }

    /**
     * 添加 full 级别构建门禁。
     *
     * @param checks 可变、有序检查清单；方法追加两项。
     */
    private static void addFullBuildChecks(final List<CheckSpec> checks) {
        checks.add(mavenCheck("quality", "Checkstyle/PMD/SpotBugs/JaCoCo", List.of("-Pquality", "verify")));
        checks.add(new CheckSpec(
                "integration",
                "无需外部中间件的集成测试",
                mavenCommand(List.of("-Pintegration-tests", "verify")),
                "zero-local-integration=ok"));
    }

    /**
     * 添加 full 级别 Starter 和独立示例检查。
     *
     * @param checks 可变、有序检查清单；方法追加六项。
     */
    private static void addExampleChecks(final List<CheckSpec> checks) {
        checks.add(new CheckSpec(
                "starter-demo",
                "本地 Starter 完整闭环",
                mavenCommand(List.of(
                        "-pl", "zero-server-starter", "-DskipTests",
                        "-Dexec.mainClass=group.zn.zero.starter.ZeroServerFullLocalDemoStart", "exec:java")),
                "demo=ok"));
        checks.add(exampleCheck(
                "example-config", "CSV 配置热重载", "examples/config-hot-reload-local/pom.xml", "config-hot-reload=ok"));
        checks.add(exampleCheck(
                "example-scheduler", "受管 Scheduler", "examples/managed-scheduler-local/pom.xml", "managed-scheduler=ok"));
        checks.add(exampleCheck(
                "example-observability", "可观测性安全门", "examples/observability-local/pom.xml",
                "zero-observability-local=ok"));
        checks.add(exampleCheck(
                "example-rpg", "RPG 本地业务闭环", "examples/rpg-minimal/pom.xml", "rpg-minimal=ok"));
        checks.add(exampleCheck(
                "example-tcp", "TCP generated dispatcher", "examples/rpg-tcp-generated/pom.xml", "rpg-tcp=ok"));
    }

    /**
     * 创建 Java source-file 检查。
     *
     * @param id 稳定检查 ID；不可为空。
     * @param description 用户可读说明；不可为空。
     * @param script 仓库相对脚本路径；不可为空。
     * @param marker 成功输出标记；不可为空。
     * @return 不可变检查规格。
     */
    private static CheckSpec javaCheck(
            final String id,
            final String description,
            final String script,
            final String marker) {
        return new CheckSpec(id, description, List.of(javaCommand(), script), marker);
    }

    /**
     * 创建 Maven Reactor 检查。
     *
     * @param id 稳定检查 ID；不可为空。
     * @param description 用户可读说明；不可为空。
     * @param arguments Maven 参数；有序、调用期间只读。
     * @return 不可变检查规格。
     */
    private static CheckSpec mavenCheck(
            final String id,
            final String description,
            final List<String> arguments) {
        return new CheckSpec(id, description, mavenCommand(arguments), "");
    }

    /**
     * 创建独立示例检查。
     *
     * @param id 稳定检查 ID；不可为空。
     * @param description 用户可读说明；不可为空。
     * @param pom 示例 POM 路径；不可为空。
     * @param marker 示例成功标记；不可为空。
     * @return 不可变检查规格。
     */
    private static CheckSpec exampleCheck(
            final String id,
            final String description,
            final String pom,
            final String marker) {
        return new CheckSpec(
                id, description, mavenCommand(List.of("-f", pom, "test", "exec:java")), marker);
    }

    /**
     * 创建需求驱动本地原型检查。
     *
     * @param outputDirectory target 下验收目录；不可为空。
     * @return 不可变检查规格。
     */
    private static CheckSpec prototypeCheck(final Path outputDirectory) {
        return new CheckSpec(
                "local-prototype",
                "需求关键词到业务首改入口",
                List.of(
                        javaCommand(), "scripts/RunLocalPrototype.java",
                        "--fromKeywords", "rpg scene sync",
                        "--projectName", "stage0-local-prototype",
                        "--packageName", "group.zn.zero.acceptance.localprototype",
                        "--outputDir", outputDirectory.resolve("prototype").toString(),
                        "--force", "--skipInstall"),
                "zero-local-prototype-runner=ok");
    }

    /**
     * 创建七类脚手架批量检查。
     *
     * @param outputDirectory target 下验收目录；不可为空。
     * @return 不可变检查规格。
     */
    private static CheckSpec scaffoldCheck(final Path outputDirectory) {
        return new CheckSpec(
                "all-scaffolds",
                "七类本地游戏脚手架",
                List.of(
                        javaCommand(), "scripts/VerifyLocalScaffolds.java",
                        "--outputDir", outputDirectory.resolve("scaffolds").toString()),
                "All local scaffolds verified: 7");
    }

    /**
     * 创建标准 Maven 命令。
     *
     * @param arguments Maven 参数；有序、调用期间只读。
     * @return 有序、不可变且非空的命令参数。
     */
    private static List<String> mavenCommand(final List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(isWindows() ? "mvn.cmd" : "mvn");
        command.add("-B");
        command.add("-ntp");
        command.add("-q");
        command.addAll(arguments);
        return List.copyOf(command);
    }

    /**
     * 返回当前运行时 Java 可执行文件。
     *
     * @return 当前 JDK 下绝对 Java 路径；不会为空。
     */
    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
    }

    /**
     * 返回子进程控制台输出编码。
     *
     * <p>Java 18+ 默认文件编码是 UTF-8，但 Windows 控制台/管道仍可能使用 native encoding。
     * 读取时按 native encoding 解码，日志写入端继续统一使用 UTF-8。</p>
     *
     * @return 当前平台支持的子进程输出编码；不会为空。
     */
    private static Charset processOutputCharset() {
        String nativeEncoding = System.getProperty("native.encoding", StandardCharsets.UTF_8.name());
        return Charset.forName(nativeEncoding);
    }

    /**
     * 校验并返回仓库根目录。
     *
     * @return 绝对、规范化仓库根目录。
     */
    private static Path requireRepositoryRoot() {
        Path root = Path.of("").toAbsolutePath().normalize();
        boolean valid = Files.isRegularFile(root.resolve("pom.xml"))
                && Files.isRegularFile(root.resolve("README.md"))
                && Files.isRegularFile(root.resolve("scripts").resolve("ZeroLocalDoctor.java"));
        if (!valid) {
            throw new IllegalArgumentException("run from the zeroServer repository root");
        }
        return root;
    }

    /**
     * 解析并限制验收输出到仓库 target 子目录。
     *
     * @param repositoryRoot 仓库根目录；不可为空。
     * @param configured 用户配置路径；不可为空。
     * @return target 下绝对、规范化子目录。
     */
    private static Path resolveOutputDirectory(final Path repositoryRoot, final Path configured) {
        Path output = configured.isAbsolute()
                ? configured.toAbsolutePath().normalize()
                : repositoryRoot.resolve(configured).normalize();
        Path targetRoot = repositoryRoot.resolve("target").normalize();
        if (output.equals(targetRoot) || !output.startsWith(targetRoot)) {
            throw new IllegalArgumentException("outputDir must be a child directory of repository target/");
        }
        return output;
    }

    /**
     * 输出 dry-run 计划。
     *
     * @param options 已解析参数；不可为空。
     * @param repositoryRoot 仓库根目录；不可为空。
     * @param outputDirectory 验收输出目录；不可为空。
     * @param checks 有序、不可变检查清单；不可为空。
     */
    private static void printPlan(
            final Options options,
            final Path repositoryRoot,
            final Path outputDirectory,
            final List<CheckSpec> checks) {
        System.out.println("zeroServer stage 0 acceptance plan");
        System.out.println("  level: " + options.level().value());
        System.out.println("  outputDir: " + repositoryRoot.relativize(outputDirectory));
        System.out.println("  timeoutSeconds: " + options.checkTimeout().toSeconds());
        for (CheckSpec check : checks) {
            System.out.println("[PLAN] " + check.id() + " - " + check.description());
            System.out.println("  $ " + String.join(" ", check.command()));
        }
        System.out.println();
        System.out.println("zero-stage0-acceptance-plan=ok"
                + "|level=" + options.level().value()
                + "|checks=" + checks.size()
                + "|externalMiddleware=false"
                + "|productionReady=false");
    }

    /**
     * 输出单项结果。
     *
     * @param repositoryRoot 仓库根目录；不可为空。
     * @param result 检查结果；不可为空。
     */
    private static void printCheckResult(final Path repositoryRoot, final CheckResult result) {
        System.out.println("[" + (result.passed() ? "PASS" : "FAIL") + "] " + result.id()
                + " - durationMs=" + result.durationMillis()
                + "|" + result.detail()
                + "|log=" + repositoryRoot.relativize(result.logFile()));
    }

    /**
     * 输出最终验收摘要。
     *
     * @param level 验收级别；不可为空。
     * @param repositoryRoot 仓库根目录；不可为空。
     * @param outputDirectory 验收输出目录；不可为空。
     * @param startedAt 验收开始的 nanoTime。
     * @param checks 计划检查清单；不可为空。
     * @param results 已执行结果；不可为空。
     */
    private static void printSummary(
            final AcceptanceLevel level,
            final Path repositoryRoot,
            final Path outputDirectory,
            final long startedAt,
            final List<CheckSpec> checks,
            final List<CheckResult> results) {
        long passed = results.stream().filter(CheckResult::passed).count();
        long failed = results.size() - passed;
        long skipped = checks.size() - results.size();
        System.out.println();
        System.out.println("zero-stage0-acceptance=" + (failed == 0 && skipped == 0 ? "ok" : "failed")
                + "|level=" + level.value()
                + "|checks=" + checks.size()
                + "|passed=" + passed
                + "|failed=" + failed
                + "|skipped=" + skipped
                + "|durationMs=" + elapsedMillis(startedAt)
                + "|externalMiddleware=false"
                + "|productionReady=false"
                + "|outputDir=" + repositoryRoot.relativize(outputDirectory));
    }

    /**
     * 创建无敏感值的单项结果详情。
     *
     * @param completed 是否在超时前完成。
     * @param exitCode 子进程退出码。
     * @param markerFound 是否找到期望标记。
     * @param expectedMarker 期望标记；可以为空。
     * @return 单行安全详情。
     */
    private static String resultDetail(
            final boolean completed,
            final int exitCode,
            final boolean markerFound,
            final String expectedMarker) {
        if (!completed) {
            return "reason=timeout|exit=124";
        }
        if (exitCode != 0) {
            return "reason=exit-code|exit=" + exitCode;
        }
        if (!markerFound) {
            return "reason=missing-marker|marker=" + expectedMarker;
        }
        return "exit=0|marker=" + (expectedMarker.isBlank() ? "not-required" : "found");
    }

    /**
     * 返回从 nanoTime 起点到当前的毫秒数。
     *
     * @param startedAt {@link System#nanoTime()} 起点。
     * @return 非负毫秒数。
     */
    private static long elapsedMillis(final long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    /**
     * 判断当前操作系统是否为 Windows。
     *
     * @return Windows 时返回 true。
     */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 检查参数中是否出现任一标记。
     *
     * @param args 参数；调用期间只读。
     * @param flags 候选标记；调用期间只读。
     * @return 命中时返回 true。
     */
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

    /**
     * 返回适合单行诊断的异常消息。
     *
     * @param exception 异常；不可为空。
     * @return 非空、无换行文本。
     */
    private static String safeMessage(final Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.replace('\r', ' ').replace('\n', ' ').replace('|', '/');
    }

    /** 输出帮助，不修改文件。 */
    private static void printHelp() {
        System.out.println("zeroServer stage 0 acceptance");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java scripts/ZeroStage0Acceptance.java --level quick");
        System.out.println("  java scripts/ZeroStage0Acceptance.java --level full");
        System.out.println("  java scripts/ZeroStage0Acceptance.java --level full --plan");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --level <quick|full>       Acceptance depth. Default: quick.");
        System.out.println("  --outputDir <target/path>  Output directory below repository target/.");
        System.out.println("  --timeoutSeconds <value>   Per-check timeout, 30..3600. Default: 600.");
        System.out.println("  --plan                     Print deterministic commands without executing them.");
        System.out.println("  --help, -h                 Print this help.");
        System.out.println();
        System.out.println("This command never connects external middleware and never proves production readiness.");
    }

    /** 验收深度。 */
    private enum AcceptanceLevel {
        /** 首次上手关键路径。 */
        QUICK("quick"),
        /** 阶段 0 全部门禁、示例和脚手架。 */
        FULL("full");

        /** 命令行稳定值。 */
        private final String value;

        AcceptanceLevel(final String value) {
            this.value = value;
        }

        private String value() {
            return value;
        }

        private static AcceptanceLevel parse(final String value) {
            for (AcceptanceLevel level : values()) {
                if (level.value.equalsIgnoreCase(value)) {
                    return level;
                }
            }
            throw new IllegalArgumentException("unsupported level: " + value);
        }
    }

    /**
     * 命令行选项。
     *
     * @param level 验收深度。
     * @param outputDirectory target 下输出目录。
     * @param checkTimeout 单项检查超时。
     * @param planOnly 是否只输出计划。
     */
    private record Options(
            AcceptanceLevel level,
            Path outputDirectory,
            Duration checkTimeout,
            boolean planOnly) {

        private static Options parse(final String[] args) {
            AcceptanceLevel level = AcceptanceLevel.QUICK;
            Path outputDirectory = DEFAULT_OUTPUT_DIR;
            Duration timeout = DEFAULT_CHECK_TIMEOUT;
            boolean planOnly = false;
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if ("--plan".equalsIgnoreCase(argument)) {
                    planOnly = true;
                    continue;
                }
                String value = requireValue(args, ++index, argument);
                if ("--level".equalsIgnoreCase(argument)) {
                    level = AcceptanceLevel.parse(value);
                } else if ("--outputDir".equalsIgnoreCase(argument)
                        || "--output-dir".equalsIgnoreCase(argument)) {
                    outputDirectory = Path.of(value);
                } else if ("--timeoutSeconds".equalsIgnoreCase(argument)
                        || "--timeout-seconds".equalsIgnoreCase(argument)) {
                    timeout = parseTimeout(value);
                } else {
                    throw new IllegalArgumentException("unsupported argument: " + argument);
                }
            }
            return new Options(level, outputDirectory, timeout, planOnly);
        }

        private static String requireValue(final String[] args, final int index, final String argument) {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new IllegalArgumentException("missing value for argument: " + argument);
            }
            return args[index];
        }

        private static Duration parseTimeout(final String value) {
            try {
                long seconds = Long.parseLong(value);
                if (seconds < MINIMUM_TIMEOUT_SECONDS || seconds > MAXIMUM_TIMEOUT_SECONDS) {
                    throw new IllegalArgumentException("timeoutSeconds must be between 30 and 3600");
                }
                return Duration.ofSeconds(seconds);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("timeoutSeconds must be an integer", exception);
            }
        }
    }

    /**
     * 单项验收规格。
     *
     * @param id 稳定检查 ID。
     * @param description 用户可读说明。
     * @param command 有序、不可变命令参数。
     * @param expectedMarker 成功输出标记；空文本表示只检查退出码。
     */
    private record CheckSpec(
            String id,
            String description,
            List<String> command,
            String expectedMarker) {

        private CheckSpec {
            command = List.copyOf(command);
        }
    }

    /**
     * 单项验收结果。
     *
     * @param id 稳定检查 ID。
     * @param passed 是否通过。
     * @param durationMillis 执行毫秒数。
     * @param logFile 完整输出日志。
     * @param detail 无敏感值单行详情。
     */
    private record CheckResult(
            String id,
            boolean passed,
            long durationMillis,
            Path logFile,
            String detail) {
    }
}
