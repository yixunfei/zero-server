import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * zeroServer 公开开发环境的只读诊断入口。
 *
 * <p>本工具检查 Java、Maven、Git、仓库根目录、核心模块、示例和脚手架入口；不会修改文件、
 * 启动网络端口、连接外部中间件或创建线程池。适合克隆仓库后的首次自检。
 *
 * @author zn
 * zero-local-doctor=ok
 */
public final class ZeroLocalDoctor {

    /** 最低 Java feature 版本。 */
    private static final int MINIMUM_JAVA_FEATURE = 21;

    /** 最低 Maven major 版本。 */
    private static final int MINIMUM_MAVEN_MAJOR = 3;

    /** 最低 Maven minor 版本。 */
    private static final int MINIMUM_MAVEN_MINOR = 9;

    /** Maven 版本提取表达式。 */
    private static final Pattern MAVEN_VERSION_PATTERN = Pattern.compile("Apache Maven\\s+(\\d+\\.\\d+(?:\\.\\d+)?)");

    /** Git 版本提取表达式。 */
    private static final Pattern GIT_VERSION_PATTERN = Pattern.compile(
            "git version\\s+(\\d+\\.\\d+(?:\\.\\d+)?(?:\\.windows\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);

    /** 必须存在的公开仓库路径；按输出顺序保持有序且不可变。 */
    private static final List<RequiredPath> REQUIRED_PATHS = List.of(
            path("root-pom", "pom.xml"),
            path("readme", "README.md"),
            path("license", "LICENSE"),
            path("contributing", "CONTRIBUTING.md"),
            path("module-map", "docs/module-map.md"),
            path("parent", "zero-parent/pom.xml"),
            path("core", "zero-core/pom.xml"),
            path("protocol", "zero-protocol/pom.xml"),
            path("actor", "zero-actor/pom.xml"),
            path("net", "zero-net/pom.xml"),
            path("starter", "zero-server-starter/pom.xml"),
            path("production-starter", "zero-server-starter-production/pom.xml"),
            path("rpg-example", "examples/rpg-minimal/pom.xml"),
            path("tcp-example", "examples/rpg-tcp-generated/pom.xml"),
            path("project-generator", "scripts/NewLocalGame.java"),
            path("prototype-runner", "scripts/RunLocalPrototype.java"),
            path("scaffold-runner", "scripts/RunLocalScaffold.java"),
            path("scaffold-verifier", "scripts/VerifyLocalScaffolds.java"),
            path("stage0-acceptance", "scripts/ZeroStage0Acceptance.java"),
            path("architecture-guard", "scripts/ZeroArchitectureGuard.java"),
            path("framework-boundary-guard", "scripts/ZeroFrameworkBoundaryGuard.java"),
            path("unified-entry-posix", "scripts/zero.sh"),
            path("unified-entry-powershell", "scripts/zero.ps1"),
            path("unified-entry-verifier", "scripts/ZeroUnifiedEntryVerifier.java"));

    /** 禁止实例化。 */
    private ZeroLocalDoctor() {
    }

    /**
     * 执行只读本地环境诊断。
     *
     * @param args 仅支持 `--help`；数组可以为空，不会被修改。
     * @throws IOException 当工具进程输出无法读取时抛出。
     * @throws InterruptedException 当等待工具进程时被中断；中断状态会被调用方观察。
     * @implNote 方法不修改项目数据；仅当前进程同步执行，不承诺多线程调用安全。
     */
    public static void main(final String[] args) throws IOException, InterruptedException {
        if (args.length > 0 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            printHelp();
            return;
        }
        if (args.length > 0) {
            System.err.println("Unknown option: " + args[0]);
            printHelp();
            System.exit(2);
        }

        List<CheckResult> results = new ArrayList<>();
        checkJava(results);
        checkMaven(results);
        checkGit(results);
        checkPaths(results);
        printResults(results);

        long failed = results.stream().filter(result -> !result.passed()).count();
        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
     * 检查当前 Java feature 版本。
     *
     * @param results 可变、有序、非线程安全结果集合；方法追加一项。
     */
    private static void checkJava(final List<CheckResult> results) {
        int feature = Runtime.version().feature();
        String detail = "required=" + MINIMUM_JAVA_FEATURE + "+|actual=" + Runtime.version();
        if (feature < MINIMUM_JAVA_FEATURE) {
            detail += "|action=select JDK " + MINIMUM_JAVA_FEATURE + "+ via JAVA_HOME and PATH";
        }
        results.add(new CheckResult(
                "java",
                feature >= MINIMUM_JAVA_FEATURE,
                detail));
    }

    /**
     * 调用 Maven 并检查版本。
     *
     * @param results 可变、有序、非线程安全结果集合；方法追加一项。
     * @throws IOException 当 Maven 进程无法启动或输出无法读取时抛出。
     * @throws InterruptedException 当等待 Maven 进程时被中断。
     */
    private static void checkMaven(final List<CheckResult> results) throws IOException, InterruptedException {
        Process process;
        try {
            process = new ProcessBuilder(mavenCommand(), "--version")
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException exception) {
            results.add(new CheckResult("maven", false, "mvn is not available on PATH"));
            return;
        }
        String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());
        int exitCode = process.waitFor();
        Matcher matcher = MAVEN_VERSION_PATTERN.matcher(output);
        String version = matcher.find() ? matcher.group(1) : "unknown";
        results.add(new CheckResult(
                "maven",
                exitCode == 0 && isSupportedMaven(version),
                "required=3.9+|actual=" + version + "|exit=" + exitCode));
    }

    /**
     * 调用 Git 并检查其是否可用。
     *
     * @param results 可变、有序、非线程安全结果集合；方法追加一项。
     * @throws IOException 当 Git 输出无法读取时抛出。
     * @throws InterruptedException 当等待 Git 进程时被中断。
     */
    private static void checkGit(final List<CheckResult> results) throws IOException, InterruptedException {
        Process process;
        try {
            process = new ProcessBuilder("git", "--version")
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException exception) {
            results.add(new CheckResult("git", false, "git is not available on PATH"));
            return;
        }
        String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());
        int exitCode = process.waitFor();
        Matcher matcher = GIT_VERSION_PATTERN.matcher(output);
        String version = matcher.find() ? matcher.group(1) : "unknown";
        results.add(new CheckResult(
                "git",
                exitCode == 0 && !"unknown".equals(version),
                "required=available|actual=" + version + "|exit=" + exitCode));
    }

    /**
     * 检查公开仓库关键路径。
     *
     * @param results 可变、有序、非线程安全结果集合；方法按固定顺序追加多项。
     */
    private static void checkPaths(final List<CheckResult> results) {
        for (RequiredPath required : REQUIRED_PATHS) {
            results.add(new CheckResult(
                    required.name(),
                    Files.exists(required.path()),
                    required.path().toString()));
        }
    }

    /**
     * 输出稳定诊断摘要。
     *
     * @param results 有序、非空、调用期间只读的结果集合；不保证线程安全。
     */
    private static void printResults(final List<CheckResult> results) {
        System.out.println("zeroServer local doctor");
        for (CheckResult result : results) {
            System.out.println("[" + (result.passed() ? "PASS" : "FAIL") + "] "
                    + result.name() + " - " + result.detail());
        }
        long passed = results.stream().filter(CheckResult::passed).count();
        long failed = results.size() - passed;
        System.out.println();
        System.out.println("zero-local-doctor=" + (failed == 0 ? "ok" : "failed")
                + "|checks=" + results.size()
                + "|passed=" + passed
                + "|failed=" + failed);
        if (failed == 0) {
            System.out.println();
            System.out.println("Next:");
            System.out.println("  java scripts/ZeroStage0Acceptance.java --level quick");
            System.out.println("  java scripts/RunLocalPrototype.java --fromKeywords \"rpg scene sync\" "
                    + "--projectName my-game --packageName group.example.mygame --force");
        }
    }

    /**
     * 判断 Maven 版本是否满足 3.9+。
     *
     * @param version Maven 版本文本；不可为空。
     * @return 满足要求时返回 true。
     */
    private static boolean isSupportedMaven(final String version) {
        String[] parts = version.split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > MINIMUM_MAVEN_MAJOR
                    || major == MINIMUM_MAVEN_MAJOR && minor >= MINIMUM_MAVEN_MINOR;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    /**
     * 返回当前平台 Maven 命令。
     *
     * @return 非空命令文本。
     */
    private static String mavenCommand() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "mvn.cmd" : "mvn";
    }

    /**
     * 创建必要路径定义。
     *
     * @param name 稳定检查名；不可为空。
     * @param value 仓库相对路径；不可为空。
     * @return 不可变路径定义。
     */
    private static RequiredPath path(final String name, final String value) {
        return new RequiredPath(name, Path.of(value));
    }

    /** 输出帮助；不修改数据。 */
    private static void printHelp() {
        System.out.println("zeroServer local doctor");
        System.out.println("Usage:");
        System.out.println("  java scripts/ZeroLocalDoctor.java");
        System.out.println("  java scripts/ZeroLocalDoctor.java --help");
        System.out.println();
        System.out.println("Checks Java 21+, Maven 3.9+, Git and public repository entry points.");
        System.out.println("If Java is below 21, set JAVA_HOME and put its bin directory first on PATH.");
    }

    /**
     * 必要路径定义。
     *
     * @param name 稳定检查名。
     * @param path 仓库相对路径。
     */
    private record RequiredPath(String name, Path path) {
    }

    /**
     * 单项检查结果。
     *
     * @param name 稳定检查名。
     * @param passed 是否通过。
     * @param detail 不含敏感信息的详情。
     */
    private record CheckResult(String name, boolean passed, String detail) {
    }
}
