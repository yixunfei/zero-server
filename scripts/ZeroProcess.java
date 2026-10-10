import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** 跨平台本地进程管理；Maven 仅构建，实际服务直接以 Java 启动并验证 JVM 身份。 @author zn */
public final class ZeroProcess {
    /** 持久状态位置，由 ZERO_STATE_DIR 覆盖。 */
    private static Path STATE = Path.of(System.getenv().getOrDefault("ZERO_STATE_DIR", "target/zero-entry"))
            .toAbsolutePath().normalize();
    /** 当前受管 JVM 元信息；不删除历史日志。 */
    private static Path META = STATE.resolve("server.properties");

    private ZeroProcess() { }

    /**
     * 执行 demo/run/serve/status/stop；超时、身份不符、启动失败返回非零。
     * @param args 命令及 --projectDir/--mainClass/--timeoutSeconds 参数。
     */
    public static void main(final String[] args) {
        try {
            if (args.length == 0) throw new IllegalArgumentException("command required");
            Map<String, String> options = options(args);
            if (options.containsKey("--stateDir")) {
                STATE = Path.of(options.get("--stateDir")).toAbsolutePath().normalize();
                META = STATE.resolve("server.properties");
            }
            Files.createDirectories(STATE);
            try (FileChannel file = FileChannel.open(STATE.resolve("entry.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = file.tryLock()) {
                if (lock == null) throw new IllegalStateException("another entry command is running");
                switch (args[0]) {
                    case "run", "demo" -> demo(options);
                    case "serve" -> serve(options);
                    case "status" -> status();
                    case "stop" -> stop(timeout(options, 15));
                    default -> throw new IllegalArgumentException("unknown process command");
                }
            }
        } catch (Exception failure) {
            System.err.println("ZERO-ENTRY-FAILED|" + failure.getMessage());
            if ("1".equals(System.getenv("ZERO_DEBUG"))) failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static Map<String, String> options(final String[] args) {
        Map<String, String> result = new HashMap<>();
        for (int index = 1; index < args.length; index += 2) {
            String key = args[index];
            if (!List.of("--projectDir", "--mainClass", "--timeoutSeconds", "--stateDir").contains(key)
                    || index + 1 >= args.length || result.putIfAbsent(key, args[index + 1]) != null) {
                throw new IllegalArgumentException("invalid or duplicate option: " + key);
            }
        }
        return result;
    }

    private static int timeout(final Map<String, String> options, final int fallback) {
        int seconds = Integer.parseInt(options.getOrDefault("--timeoutSeconds", Integer.toString(fallback)));
        if (seconds < 1 || seconds > 60) throw new IllegalArgumentException("timeoutSeconds must be 1..60");
        return seconds;
    }

    private static Launch prepare(final Map<String, String> options, final boolean server) throws Exception {
        String configured = options.get("--projectDir");
        if (configured == null) throw new IllegalArgumentException("--projectDir required");
        Path project = Path.of(configured).toAbsolutePath().normalize();
        Path pom = project.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) throw new IllegalArgumentException("project pom.xml missing");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(pom.toFile());
        var names = document.getElementsByTagName("exec.mainClass");
        String main = options.get("--mainClass");
        if (main == null) {
            if (names.getLength() != 1) throw new IllegalArgumentException("--mainClass required for this project");
            main = names.item(0).getTextContent().trim() + (server ? "Server" : "");
        }
        if (!main.matches("[A-Za-z_$][A-Za-z0-9_$.]*")) throw new IllegalArgumentException("invalid mainClass");
        Path run = Files.createDirectory(STATE.resolve("run-" + UUID.randomUUID()));
        Path classpathFile = run.resolve("classpath.txt");
        String maven = System.getenv("ZERO_MAVEN_CMD");
        if (maven == null || maven.isBlank()) maven = Path.of(isWindows() ? "mvnw.cmd" : "mvnw").toAbsolutePath().toString();
        var build = new ProcessBuilder(maven, "-q", "-f", pom.toString(), "compile",
                "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath",
                "-Dmdep.outputFile=" + classpathFile).inheritIO().start();
        if (!build.waitFor(180, TimeUnit.SECONDS)) {
            terminate(build.toHandle());
            throw new IllegalStateException("build timed out");
        }
        if (build.exitValue() != 0) throw new IllegalStateException("build failed: exit=" + build.exitValue());
        String classpath = project.resolve("target/classes") + java.io.File.pathSeparator
                + Files.readString(classpathFile).trim();
        // Java 参数文件避免 Windows 长 classpath 超出进程命令行上限。
        Path arguments = run.resolve("java.args");
        Files.writeString(arguments, "-cp\n" + quoted(classpath) + "\n" + main + "\n");
        List<String> command = List.of(java(), "@" + arguments);
        return new Launch(project, run, command);
    }

    private static void demo(final Map<String, String> options) throws Exception {
        Launch launch = prepare(options, false);
        ProcessBuilder builder = new ProcessBuilder(launch.command()).directory(launch.project().toFile()).inheritIO();
        builder.environment().remove("ZERO_ENTRY_CONTROL");
        builder.environment().remove("ZERO_ENTRY_TOKEN");
        Process process = builder.start();
        int exit = process.waitFor();
        if (exit != 0) throw new IllegalStateException("demo failed: exit=" + exit);
        System.out.println("zero-run=completed|mode=demo|exit=0");
    }

    private static void serve(final Map<String, String> options) throws Exception {
        if (Files.exists(META)) {
            Properties previous = read(META);
            if (ProcessHandle.of(Long.parseLong(previous.getProperty("pid"))).filter(ProcessHandle::isAlive).isPresent()) {
                throw new IllegalStateException("managed state is occupied; inspect status and stop first");
            }
            cleanupControls(previous);
            Files.deleteIfExists(META);
        }
        int seconds = timeout(options, 30);
        Launch launch = prepare(options, true);
        String token = UUID.randomUUID().toString();
        ProcessBuilder builder = new ProcessBuilder(launch.command()).directory(launch.project().toFile())
                .redirectOutput(launch.directory().resolve("server.log").toFile())
                .redirectError(launch.directory().resolve("server.error.log").toFile());
        builder.environment().put("ZERO_ENTRY_CONTROL", launch.directory().toString());
        builder.environment().put("ZERO_ENTRY_TOKEN", token);
        Process process = builder.start();
        Properties meta = new Properties();
        try {
            meta.setProperty("pid", Long.toString(process.pid()));
            meta.setProperty("start", process.info().startInstant().orElseThrow().toString());
            meta.setProperty("directory", launch.directory().toString());
            meta.setProperty("project", launch.project().toString());
            meta.setProperty("token", token);
            write(META, meta);
            long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) throw new IllegalStateException("server exited: " + process.exitValue());
                if (ready(meta) && process.isAlive()) {
                    System.out.println("zero-serve=ready|pid=" + process.pid() + "|logs=" + launch.directory());
                    return;
                }
                Thread.sleep(100L);
            }
            throw new IllegalStateException("server readiness timeout");
        } catch (Exception failure) {
            terminate(process.toHandle());
            if (!process.isAlive()) {
                cleanupControls(meta);
                Files.deleteIfExists(META);
                Files.deleteIfExists(META.resolveSibling("server.properties.tmp"));
            }
            throw new IllegalStateException(failure.getMessage() + "|logs=" + launch.directory(), failure);
        }
    }

    private static ProcessHandle identity(final Properties meta) {
        var handle = ProcessHandle.of(Long.parseLong(meta.getProperty("pid")));
        if (handle.isEmpty() || !handle.get().isAlive()) return null;
        if (!handle.get().info().startInstant().map(Object::toString).orElse("").equals(meta.getProperty("start"))) {
            throw new IllegalStateException("managed-pid-identity-mismatch");
        }
        return handle.get();
    }

    private static boolean ready(final Properties meta) throws IOException {
        Path ready = controlDirectory(meta).resolve("ready");
        if (!Files.isRegularFile(ready)) return false;
        Properties state = read(ready);
        return List.of("pid", "start", "token").stream()
                .allMatch(key -> meta.getProperty(key).equals(state.getProperty(key)));
    }

    private static void status() throws Exception {
        if (!Files.exists(META)) { System.out.println("zero-status=idle"); return; }
        Properties meta = read(META);
        ProcessHandle handle = identity(meta);
        String status = handle == null ? "exited" : ready(meta) && handle.isAlive() ? "ready" : "not-ready";
        System.out.println("zero-status=" + status + "|pid=" + meta.getProperty("pid")
                + "|logs=" + meta.getProperty("directory"));
    }

    private static void stop(final int seconds) throws Exception {
        if (!Files.exists(META)) { System.out.println("zero-stop=idle|managed=false"); return; }
        Properties meta = read(META);
        ProcessHandle handle = identity(meta);
        if (handle == null) {
            cleanupControls(meta);
            Files.deleteIfExists(META);
            System.out.println("zero-stop=already-stopped");
            return;
        }
        Path signal = controlDirectory(meta).resolve("stop");
        if (Files.isSymbolicLink(signal)) throw new IOException("managed stop signal must not be a symbolic link");
        Files.writeString(signal, meta.getProperty("token"));
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (handle.isAlive() && System.nanoTime() < deadline) Thread.sleep(100L);
        boolean forced = handle.isAlive();
        if (forced) {
            identity(meta);
            terminate(handle);
        }
        if (handle.isAlive()) throw new IllegalStateException("shutdown failed; managed state retained");
        cleanupControls(meta);
        Files.deleteIfExists(META);
        System.out.println("zero-stop=stopped|pid=" + handle.pid() + "|forced=" + forced);
    }

    /** 只清理当前状态根下本次 run 的敏感控制文件，保留日志和参数用于诊断。 */
    private static void cleanupControls(final Properties meta) throws IOException {
        String configured = meta.getProperty("directory");
        if (configured == null) return;
        Path run = controlDirectory(meta);
        for (String name : List.of("ready", "ready.tmp", "stop")) Files.deleteIfExists(run.resolve(name));
    }

    /** 控制文件读写共用归属校验；真实路径检查也拒绝 Windows junction 越界。 */
    private static Path controlDirectory(final Properties meta) throws IOException {
        Path run = Path.of(meta.getProperty("directory")).toAbsolutePath().normalize();
        if (!STATE.equals(run.getParent()) || !run.getFileName().toString().matches("run-[0-9a-fA-F-]{36}")
                || Files.isSymbolicLink(run)
                || Files.exists(run) && !STATE.toRealPath().equals(run.toRealPath().getParent())) {
            throw new IOException("managed control directory is outside state root");
        }
        return run;
    }

    private static void terminate(final ProcessHandle handle) throws Exception {
        List<ProcessHandle> children = handle.descendants().toList();
        children.forEach(ProcessHandle::destroy);
        handle.destroy();
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while ((handle.isAlive() || children.stream().anyMatch(ProcessHandle::isAlive)) && System.nanoTime() < deadline) {
            Thread.sleep(50L);
        }
        children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (handle.isAlive()) handle.destroyForcibly();
        deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while ((handle.isAlive() || children.stream().anyMatch(ProcessHandle::isAlive))
                && System.nanoTime() < deadline) Thread.sleep(50L);
        if (handle.isAlive() || children.stream().anyMatch(ProcessHandle::isAlive)) {
            throw new IllegalStateException("process cleanup failed");
        }
    }

    private static Properties read(final Path path) throws IOException {
        Properties value = new Properties();
        try (var input = Files.newInputStream(path)) { value.load(input); }
        return value;
    }

    private static void write(final Path path, final Properties value) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (var output = Files.newOutputStream(temporary)) { value.store(output, "zero managed JVM"); }
        Files.move(temporary, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static String quoted(final String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean isWindows() { return System.getProperty("os.name").startsWith("Windows"); }
    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
    }
    /** 本次启动的路径、日志目录和类型安全参数列表。 */
    private record Launch(Path project, Path directory, List<String> command) { }
}
