import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * zero-codegen project-scaffold 的仓库薄启动器。
 *
 * <p>模板、关键词、能力和依赖映射均由 zero-codegen 管理；本文件只检查仓库环境、
 * 按需构建工具并原样转发参数。</p>
 *
 * @author zn
 */
public final class NewLocalGame {

    private static final String MAIN_CLASS = "group.zn.zero.codegen.scaffold.ProjectScaffoldCli";

    private NewLocalGame() {
    }

    /**
     * 启动项目生成工具。
     *
     * @param args 原样转发给 zero-codegen。
     * @throws IOException 进程启动或仓库检查失败。
     * @throws InterruptedException 等待工具时被中断。
     */
    public static void main(final String[] args) throws IOException, InterruptedException {
        if (hasFlag(args, "--help", "-h")) {
            printHelp();
            return;
        }
        Path repositoryRoot;
        try {
            ensureJava21();
            repositoryRoot = locateRepositoryRoot();
        } catch (IllegalStateException exception) {
            System.err.println("NEW-LOCAL-GAME-ENVIRONMENT|" + exception.getMessage());
            System.err.println("Hint: run `java scripts/ZeroLocalDoctor.java` after selecting JDK 21.");
            System.exit(1);
            return;
        }
        Path toolJar = locateToolJar(repositoryRoot);
        if (toolJar == null) {
            buildTool(repositoryRoot);
            toolJar = locateToolJar(repositoryRoot);
        }
        if (toolJar == null) {
            throw new IllegalStateException("zero-codegen shaded jar was not produced");
        }
        List<String> command = new ArrayList<>();
        command.add(javaCommand());
        command.add("-cp");
        command.add(toolJar.toString());
        command.add(MAIN_CLASS);
        if (java.util.Arrays.stream(args).noneMatch(arg -> arg.equalsIgnoreCase("--templateRoot"))) {
            command.add("--templateRoot");
            command.add(repositoryRoot.resolve("templates").toString());
        }
        command.addAll(List.of(args));
        int exitCode = run(command, repositoryRoot);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static Path locateRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("zero-codegen/pom.xml"))
                    && Files.isDirectory(current.resolve("templates"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("run this launcher from the zeroServer repository");
    }

    private static Path locateToolJar(final Path repositoryRoot) throws IOException {
        Path target = repositoryRoot.resolve("zero-codegen/target");
        if (!Files.isDirectory(target)) {
            return null;
        }
        try (var files = Files.list(target)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("zero-codegen-.+-all\\.jar"))
                    .max(Comparator.comparing(NewLocalGame::lastModified))
                    .orElse(null);
        }
    }

    private static FileTime lastModified(final Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException ignored) {
            return FileTime.fromMillis(0L);
        }
    }

    private static void buildTool(final Path repositoryRoot) throws IOException, InterruptedException {
        System.out.println("zero-codegen scaffold tool is not built; packaging it now...");
        int exitCode = run(List.of(
                mavenCommand(repositoryRoot),
                "-q",
                "-pl",
                "zero-codegen",
                "-am",
                "-DskipTests",
                "package"), repositoryRoot);
        if (exitCode != 0) {
            throw new IllegalStateException("failed to package zero-codegen; Maven exit code=" + exitCode);
        }
    }

    private static int run(final List<String> command, final Path directory)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .inheritIO();
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        return builder.start().waitFor();
    }

    private static String javaCommand() {
        String binary = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", binary).toString();
    }

    private static String mavenCommand(final Path repositoryRoot) {
        String configured = System.getenv("ZERO_MAVEN_CMD");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        Path wrapper = repositoryRoot.resolve(isWindows() ? "mvnw.cmd" : "mvnw");
        if (Files.isRegularFile(wrapper) && (isWindows() || Files.isExecutable(wrapper))) {
            return wrapper.toString();
        }
        if (isWindows()) {
            return "mvn.cmd";
        }
        return "mvn";
    }

    private static void ensureJava21() {
        int feature = Runtime.version().feature();
        if (feature < 21) {
            throw new IllegalStateException("zeroServer requires Java 21+. Current Java is " + feature
                    + ". Set JAVA_HOME to a JDK 21 installation and put its bin directory first on PATH.");
        }
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

    private static void printHelp() {
        System.out.println("zeroServer local game scaffold generator");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java scripts/NewLocalGame.java [options]");
        System.out.println();
        System.out.println("The command builds zero-codegen when needed, then forwards options to the generator.");
        System.out.println("Java 21+ is required for generation. Maven uses mvnw/mvnw.cmd when present.");
        System.out.println();
        System.out.println("Common options:");
        System.out.println("  --projectName <name>      Project name. Default: zero-local-game.");
        System.out.println("  --packageName <package>   Java package. Default: group.zn.zero.localgame.");
        System.out.println("  --outputDir <path>        New project directory. Default: projectName.");
        System.out.println("  --template <id>           Template. Default: local; use runtime for an empty kernel.");
        System.out.println("  --fromKeywords <words>    Select a template from business keywords.");
        System.out.println("  --components <ids>        Optional components, comma-separated.");
        System.out.println("  --zeroVersion <version>   Override the framework dependency version.");
        System.out.println("  --listTemplates           Show the template catalog (requires Java 21+).");
        System.out.println("  --describeTemplate <id>   Show a template and its example command.");
        System.out.println("  --help, -h                Show this help without building or writing files.");
        System.out.println();
        System.out.println("Example: java scripts/NewLocalGame.java --projectName my-game "
                + "--outputDir target/my-game");
        System.out.println("All options, components and upgrade operations: docs/scaffold-templates.zh-CN.md");
        System.out.println("Generate, test and run in one command: java scripts/RunLocalPrototype.java");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }
}
