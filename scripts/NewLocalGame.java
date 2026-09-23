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
        Path repositoryRoot = locateRepositoryRoot();
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
                mavenCommand(),
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

    private static String mavenCommand() {
        String configured = System.getenv("ZERO_MAVEN_CMD");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        if (isWindows()) {
            return "mvn.cmd";
        }
        return Files.isExecutable(Path.of("mvnw")) ? "./mvnw" : "mvn";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }
}
