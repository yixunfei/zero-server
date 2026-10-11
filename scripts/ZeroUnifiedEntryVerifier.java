import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Verifies the cross-platform zeroServer entrypoint contract with optional generated loopback process smoke checks.
 *
 * @author zn
 */
public final class ZeroUnifiedEntryVerifier {

    private ZeroUnifiedEntryVerifier() {
    }

    /**
     * Runs static and POSIX smoke checks for the unified entrypoint.
     *
     * @param args optional --full-smoke runs generated projects and controlled subprocesses
     * @throws Exception when a required check fails
     */
    public static void main(final String[] args) throws Exception {
        boolean fullSmoke = List.of(args).contains("--full-smoke");
        Path root = Path.of("").toAbsolutePath().normalize();
        require(Files.isRegularFile(root.resolve("mvnw")), "mvnw exists");
        require(Files.isRegularFile(root.resolve("mvnw.cmd")), "mvnw.cmd exists");
        require(Files.isRegularFile(root.resolve(".mvn/wrapper/maven-wrapper.properties")), "wrapper properties exist");
        require(Files.isRegularFile(root.resolve(".mvn/toolchains.xml")), "fixed toolchain exists");
        require(Files.isRegularFile(root.resolve("scripts/zero.sh")), "scripts/zero.sh exists");
        require(Files.isRegularFile(root.resolve("scripts/zero.ps1")), "scripts/zero.ps1 exists");
        String wrapperProperties = Files.readString(root.resolve(".mvn/wrapper/maven-wrapper.properties"), StandardCharsets.UTF_8);
        require(wrapperProperties.contains("apache-maven/3.9.16"), "wrapper pins Maven 3.9.16");
        String shell = Files.readString(root.resolve("scripts/zero.sh"), StandardCharsets.UTF_8);
        String powershell = Files.readString(root.resolve("scripts/zero.ps1"), StandardCharsets.UTF_8);
        for (String command : List.of("doctor", "init", "generate", "test", "diagnose", "run", "demo", "serve", "status", "stop")) {
            require(shell.contains("  " + command), "POSIX documents " + command);
            require(powershell.contains("'" + command + "'"), "PowerShell dispatches " + command);
        }
        String process = Files.readString(root.resolve("scripts/ZeroProcess.java"), StandardCharsets.UTF_8);
        require(shell.contains("scripts/ZeroProcess.java") && powershell.contains("scripts/ZeroProcess.java"),
                "both launchers delegate process control");
        require(process.contains("startInstant()") && process.contains("managed-pid-identity-mismatch"),
                "controller validates JVM identity");
        require(process.contains("server readiness timeout") && process.contains("ZERO_ENTRY_TOKEN"),
                "controller waits for authenticated readiness");
        if (fullSmoke) {
            runFullSmoke(root);
            return;
        }
        if (isWindows()) {
            System.out.println("zero-unified-entry-verifier=ok|platform=windows|posixStatic=true|powershellStatic=true");
            return;
        }
        run(List.of("bash", "-n", "scripts/zero.sh"));
        String help = capture(List.of("bash", "scripts/zero.sh", "help"));
        require(help.contains("zeroServer unified entrypoint"), "POSIX help");
        int invalid = exitCode(List.of("bash", "scripts/zero.sh", "unknown-command"));
        require(invalid == 2, "POSIX unknown command exit code");
        Path state = root.resolve("target/zero-unified-entry-verifier");
        run(List.of("bash", "-c", "ZERO_STATE_DIR=\"" + state + "\" scripts/zero.sh stop"));
        run(List.of("bash", "-c", "ZERO_STATE_DIR=\"" + state + "\" scripts/zero.sh stop"));
        System.out.println("zero-unified-entry-verifier=ok|platform=posix|help=true|invalidCommand=true|stopIdempotent=true");
    }

    private static void runFullSmoke(final Path root) throws Exception {
        Path output = root.resolve("target/cross-platform").resolve(platformName())
                .resolve("entry-" + java.util.UUID.randomUUID());
        Files.createDirectories(output);
        Path project = output.resolve("generated local net");
        String entry = isWindows() ? "scripts/zero.ps1" : "scripts/zero.sh";
        String prefix = isWindows() ? "powershell.exe" : "bash";
        runCapture(output.resolve("doctor.log"), command(prefix, entry, "doctor"));
        runCapture(output.resolve("generate.log"), command(prefix, entry, "generate", "--template", "local",
                "--components", "net", "--projectName", "cross-platform-local",
                "--packageName", "group.example.crossplatform", "--outputDir", project.toString()));
        runCapture(output.resolve("diagnose.log"), command(prefix, entry, "diagnose", "--projectDir", project.toString()));
        runCapture(output.resolve("test.log"), command(prefix, entry, "test", "--projectDir", project.toString()));
        String demo = runCapture(output.resolve("run.log"), command(prefix, entry, "run", "--projectDir", project.toString()));
        require(demo.contains("zero-run=completed"), "foreground demo completes");
        try {
            String started = runCapture(output.resolve("serve.log"),
                    command(prefix, entry, "serve", "--projectDir", project.toString()));
            require(started.contains("zero-serve=ready"), "server reaches actual readiness");
            String status = runCapture(output.resolve("status.log"), command(prefix, entry, "status"));
            require(status.contains("zero-status=ready"), "status confirms readiness");
            verifyIdentityMismatch(output, prefix, entry);
            java.util.Properties metadata = new java.util.Properties();
            try (var stream = Files.newInputStream(output.resolve("entry-state/server.properties"))) {
                metadata.load(stream);
            }
            Path runDirectory = Path.of(metadata.getProperty("directory"));
            String stopped = runCapture(output.resolve("stop.log"), command(prefix, entry, "stop"));
            require(stopped.contains("zero-stop=stopped") && stopped.contains("forced=false"), "graceful stop");
            require(!Files.exists(runDirectory.resolve("stop")) && !Files.exists(runDirectory.resolve("ready")),
                    "sensitive control files removed");
            require(Files.isRegularFile(runDirectory.resolve("server.log")), "diagnostic log retained");
            require(runCapture(output.resolve("stop-again.log"), command(prefix, entry, "stop"))
                    .contains("zero-stop=idle"), "stop is idempotent");
            require(runCapture(output.resolve("idle.log"), command(prefix, entry, "status"))
                    .contains("zero-status=idle"), "state removed after stop");
        } finally {
            runCapture(output.resolve("cleanup.log"), command(prefix, entry, "stop"));
        }
        verifyStartupFailures(output, project, prefix, entry);
        verifyInterruptedStops(output, project, prefix, entry);
        try (var files = Files.walk(output.resolve("entry-state"))) {
            require(files.noneMatch(path -> List.of("ready", "ready.tmp", "stop", "server.properties", "server.properties.tmp")
                    .contains(path.getFileName().toString())), "all exit paths remove sensitive controls");
        }
        System.out.println("zero-unified-entry-verifier=ok|platform=" + platformName()
                + "|fullSmoke=true|demo=true|serve=true|status=true|gracefulStop=true|spacePaths=true"
                + "|identityRefusal=true|startupFailures=true|forcedStop=true|alreadyExited=true|controlsClean=true|logs=" + output);
    }

    /** 用真实独立 JVM 覆盖强制关闭与已退出元数据清理，不依赖业务 shutdown hook。 */
    private static void verifyInterruptedStops(final Path output, final Path project, final String prefix,
            final String entry) throws Exception {
        Files.writeString(project.resolve("src/main/java/EntryIgnoresStop.java"), """
                /** Entry verifier fixture: authentic readiness, deliberately ignores stop. */
                public final class EntryIgnoresStop {
                    public static void main(String[] args) throws Exception {
                        var process = ProcessHandle.current();
                        var ready = new java.util.Properties();
                        ready.setProperty("pid", Long.toString(process.pid()));
                        ready.setProperty("start", process.info().startInstant().orElseThrow().toString());
                        ready.setProperty("token", System.getenv("ZERO_ENTRY_TOKEN"));
                        var directory = java.nio.file.Path.of(System.getenv("ZERO_ENTRY_CONTROL"));
                        try (var stream = java.nio.file.Files.newOutputStream(directory.resolve("ready"))) {
                            ready.store(stream, "test readiness");
                        }
                        Thread.sleep(600_000L);
                    }
                }
                """);
        for (boolean alreadyExited : List.of(false, true)) {
            String label = alreadyExited ? "already-exited" : "forced";
            try {
                runCapture(output.resolve(label + "-serve.log"), command(prefix, entry, "serve",
                        "--projectDir", project.toString(), "--mainClass", "EntryIgnoresStop"));
                var meta = new java.util.Properties();
                try (var input = Files.newInputStream(output.resolve("entry-state/server.properties"))) { meta.load(input); }
                Path run = Path.of(meta.getProperty("directory"));
                if (alreadyExited) {
                    ProcessHandle process = ProcessHandle.of(Long.parseLong(meta.getProperty("pid"))).orElseThrow();
                    process.destroyForcibly();
                    process.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
                }
                String stopped = runCapture(output.resolve(label + "-stop.log"),
                        command(prefix, entry, "stop", "--timeoutSeconds", "1"));
                require(stopped.contains(alreadyExited ? "zero-stop=already-stopped" : "forced=true"), label + " stop");
                require(!Files.exists(run.resolve("ready")) && !Files.exists(run.resolve("stop")), label + " controls removed");
                require(Files.isRegularFile(run.resolve("server.log")), label + " logs retained");
            } finally {
                runCapture(output.resolve(label + "-cleanup.log"), command(prefix, entry, "stop", "--timeoutSeconds", "1"));
            }
        }
    }

    private static void verifyIdentityMismatch(final Path output, final String prefix, final String entry)
            throws Exception {
        Path metadata = output.resolve("entry-state/server.properties");
        byte[] original = Files.readAllBytes(metadata);
        java.util.Properties properties = new java.util.Properties();
        try (var input = Files.newInputStream(metadata)) { properties.load(input); }
        long pid = Long.parseLong(properties.getProperty("pid"));
        properties.setProperty("start", "1970-01-01T00:00:00Z");
        try {
            try (var stream = Files.newOutputStream(metadata)) { properties.store(stream, "stale PID fixture"); }
            String rejected = runCapture(output.resolve("identity-refusal.log"),
                    command(prefix, entry, "stop"), 1);
            require(rejected.contains("managed-pid-identity-mismatch"), "stale identity refuses stop");
            require(ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isPresent(), "unverified JVM stays alive");
        } finally {
            Files.write(metadata, original);
        }
    }

    private static void verifyStartupFailures(final Path output, final Path project, final String prefix,
            final String entry) throws Exception {
        String missing = runCapture(output.resolve("missing-main.log"), command(prefix, entry, "serve",
                "--projectDir", project.toString(), "--mainClass", "NoSuchEntry", "--timeoutSeconds", "1"), 1);
        require(missing.contains("ZERO-ENTRY-FAILED") && !missing.contains("zero-serve=ready"),
                "missing main fails without reporting ready");
        // 只在本次新生成的临时工程中增加失败夹具，不覆盖业务生成物。
        Files.writeString(project.resolve("src/main/java/EntryNeverReady.java"), """
                /** Entry verifier fixture: writes identity but never publishes readiness. */
                public final class EntryNeverReady {
                    public static void main(String[] args) throws Exception {
                        java.nio.file.Files.writeString(java.nio.file.Path.of("fixture.pid"),
                                Long.toString(ProcessHandle.current().pid()));
                        Thread.sleep(600_000L);
                    }
                }
                """);
        try {
            String timeout = runCapture(output.resolve("no-ready.log"), command(prefix, entry, "serve",
                    "--projectDir", project.toString(), "--mainClass", "EntryNeverReady", "--timeoutSeconds", "1"), 1);
            require(timeout.contains("server readiness timeout"), "unready process times out");
            long pid = Long.parseLong(Files.readString(project.resolve("fixture.pid")));
            require(ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isEmpty(), "timed out process is reaped");
            require(!Files.exists(output.resolve("entry-state/server.properties")), "failed start state cleared");
        } finally {
            runCapture(output.resolve("failure-cleanup.log"), command(prefix, entry, "stop"));
        }
    }

    private static List<String> command(final String launcher, final String entry, final String... args) {
        List<String> command = new java.util.ArrayList<>();
        command.add(launcher);
        if (isWindows()) {
            command.add("-NoProfile");
            command.add("-NonInteractive");
            command.add("-ExecutionPolicy");
            command.add("Bypass");
            command.add("-File");
        }
        command.add(entry);
        command.addAll(List.of(args));
        return command;
    }

    private static String runCapture(final Path log, final List<String> command)
            throws IOException, InterruptedException {
        return runCapture(log, command, 0);
    }

    private static String runCapture(final Path log, final List<String> command, final int expectedExit)
            throws IOException, InterruptedException {
        List<String> isolated = new java.util.ArrayList<>(command);
        int entry = isolated.indexOf(isWindows() ? "scripts/zero.ps1" : "scripts/zero.sh");
        if (entry >= 0 && isolated.size() > entry + 1
                && List.of("run", "demo", "serve", "status", "stop").contains(isolated.get(entry + 1))) {
            isolated.addAll(List.of("--stateDir", log.getParent().resolve("entry-state").toString()));
        }
        ProcessBuilder builder = new ProcessBuilder(isolated).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("ZERO_STATE_DIR", log.getParent().resolve("entry-state").toString());
        builder.environment().put("ZERO_NET_PORT", "0");
        Process process = builder.start();
        if (!process.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new IllegalStateException("command timed out; log=" + log);
        }
        // 原始日志保留；Windows 子进程可能混用本地编码，ASCII 契约检查不能因诊断编码失败。
        String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        if (process.exitValue() != expectedExit) {
            System.out.print(output);
            throw new IllegalStateException("command failed; log=" + log);
        }
        return output;
    }

    private static String platformName() {
        if (isWindows()) {
            return "windows";
        }
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return os.contains("mac") || os.contains("darwin") ? "macos" : "linux";
    }

    private static void run(final List<String> command) throws IOException, InterruptedException {
        int exit = new ProcessBuilder(command).inheritIO().start().waitFor();
        require(exit == 0, "command succeeded: " + String.join(" ", command));
    }

    private static String capture(final List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        require(process.waitFor() == 0, "command succeeded: " + String.join(" ", command));
        return output;
    }

    private static int exitCode(final List<String> command) throws IOException, InterruptedException {
        return new ProcessBuilder(command).redirectErrorStream(true).start().waitFor();
    }

    private static void require(final boolean condition, final String label) {
        if (!condition) {
            throw new IllegalStateException("FAIL: " + label);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
