import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Verifies the cross-platform zeroServer entrypoint contract without building or connecting to services.
 *
 * @author zn
 */
public final class ZeroUnifiedEntryVerifier {

    private ZeroUnifiedEntryVerifier() {
    }

    /**
     * Runs static and POSIX smoke checks for the unified entrypoint.
     *
     * @param args ignored
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
        require(wrapperProperties.contains("apache-maven/3.9.8"), "wrapper pins Maven 3.9.8");
        String shell = Files.readString(root.resolve("scripts/zero.sh"), StandardCharsets.UTF_8);
        String powershell = Files.readString(root.resolve("scripts/zero.ps1"), StandardCharsets.UTF_8);
        for (String command : List.of("doctor", "init", "generate", "test", "diagnose", "run", "stop")) {
            require(shell.contains("  " + command), "POSIX documents " + command);
            require(powershell.contains("'" + command + "'"), "PowerShell dispatches " + command);
        }
        require(shell.contains("server.pid") && shell.contains("server.meta"), "POSIX uses controlled state");
        require(powershell.contains("server.pid") && powershell.contains("server.meta"), "PowerShell uses controlled state");
        require(shell.contains("zero.entry.projectDir") && powershell.contains("zero.entry.projectDir"), "run records project identity");
        require(shell.contains("managed-pid-identity-mismatch"), "POSIX validates managed PID identity");
        require(powershell.contains("managed PID identity mismatch"), "PowerShell validates managed PID identity");
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
        Path output = root.resolve("target/cross-platform").resolve(platformName());
        deleteRecursively(output);
        Files.createDirectories(output);
        Path project = output.resolve("generated-scene-sync");
        String entry = isWindows() ? "scripts\\zero.ps1" : "scripts/zero.sh";
        String prefix = isWindows() ? "powershell.exe" : "bash";
        Path state = output.resolve("entry-state");
        if (!isWindows()) {
            run(List.of("chmod", "+x", "mvnw", "scripts/zero.sh"));
        }
        runCapture(output.resolve("doctor.log"), command(prefix, entry, "doctor"));
        runCapture(output.resolve("generate.log"), command(prefix, entry, "generate", "--template", "scene-sync",
                "--projectName", "cross-platform-scene", "--packageName", "group.example.crossplatform",
                "--outputDir", project.toString(), "--force"));
        runCapture(output.resolve("diagnose.log"), command(prefix, entry, "diagnose", "--projectDir", project.toString()));
        runCapture(output.resolve("test.log"), command(prefix, entry, "test", "--projectDir", project.toString()));
        runCapture(output.resolve("run.log"), command(prefix, entry, "run", "--projectDir", project.toString()));
        Thread.sleep(3_000L);
        runCapture(output.resolve("stop.log"), command(prefix, entry, "stop"));
        runCapture(output.resolve("stop-again.log"), command(prefix, entry, "stop"));
        System.out.println("zero-unified-entry-verifier=ok|platform=" + platformName()
                + "|fullSmoke=true|doctor=true|generate=true|diagnose=true|test=true|run=true|stop=true");
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

    private static List<String> withState(final List<String> command, final Path state) {
        List<String> result = new java.util.ArrayList<>();
        if (isWindows()) {
            result.addAll(List.of("-Command", "$env:ZERO_STATE_DIR='" + state + "'; & " + String.join(" ", command.subList(1, command.size()))));
        } else {
            result.addAll(List.of("-c", "ZERO_STATE_DIR=\"" + state + "\" " + String.join(" ", command)));
        }
        return isWindows() ? List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", result.get(1)) : List.of("bash", result.get(0), result.get(1));
    }
    private static void runCapture(final Path log, final List<String> command)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        Files.write(log, output);
        if (process.waitFor() != 0) {
            System.out.write(output);
            throw new IllegalStateException("command failed: " + String.join(" ", command));
        }
    }

    private static String platformName() {
        if (isWindows()) {
            return "windows";
        }
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return os.contains("mac") || os.contains("darwin") ? "macos" : "linux";
    }

    private static void deleteRecursively(final Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(candidate -> {
                try {
                    Files.deleteIfExists(candidate);
                } catch (IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            });
        } catch (java.io.UncheckedIOException exception) {
            throw exception.getCause();
        }
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
