import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Independent platform and production-focused acceptance gate with fresh evidence. */
public final class ZeroPlatformProductionGate {
    private static final Duration TIMEOUT = Duration.ofMinutes(15);

    private ZeroPlatformProductionGate() { }

    public static void main(final String[] args) throws Exception {
        Mode mode = Mode.parse(args);
        Path root = Path.of("").toAbsolutePath().normalize();
        Path output = root.resolve("target/production-gate").resolve(UUID.randomUUID().toString().replace("-", ""));
        Files.createDirectories(output);
        List<Result> results = switch (mode) {
            case PLATFORM_TRANSACTION -> List.of(run(root, output, "platform-transaction", List.of(maven(), "-B", "-ntp", "-pl", "zero-codegen",
                    "-Dtest=ProjectScaffoldGeneratorTest,ScaffoldManifestContractTest,ScaffoldCliValidationTest", "test"), "BUILD SUCCESS"));
            case LOCAL_PRODUCTION_FOCUSED -> List.of(
                    run(root, output, "network-focused", List.of(maven(), "-B", "-ntp", "-pl", "zero-net,zero-runtime-net,zero-server-starter-production", "-am",
                            "-Dtest=ProductionNetworkLifecycleFocusedTest,ProductionNetworkTelemetryObserverTest,ProductionNetworkProviderTest",
                            "-Dsurefire.failIfNoSpecifiedTests=false", "test"), "BUILD SUCCESS"),
                    run(root, output, "gm-focused", List.of(maven(), "-B", "-ntp", "-pl", "zero-gm,zero-gm-rest", "-am", "test"), "BUILD SUCCESS"),
                    run(root, output, "release-artifact", List.of(javaCommand(), "scripts/ZeroReleaseArtifactEvidence.java"), "zero-release-artifact-evidence=passed"),
                    run(root, output, "performance-gate", List.of(maven(), "-B", "-ntp", "-Pperformance-gate", "-f", "zero-benchmarks/pom.xml", "-am", "verify"), "protocol-codec-gate=ok"));
            case EXTERNAL_PRODUCTION -> List.of(run(root, output, "kafka-external", List.of(powerShell(), "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", "scripts/VerifyCenterLogicKafka.ps1"), "center-logic-kafka=passed"));
        };
        long failed = results.stream().filter(r -> !r.status.equals("passed")).count();
        StringBuilder manifest = new StringBuilder("{\n  \"schema\":\"zero-platform-production-gate/v1\",\n");
        manifest.append("  \"mode\":\"").append(mode.name().toLowerCase(Locale.ROOT)).append("\",\n");
        manifest.append("  \"os\":\"").append(escape(System.getProperty("os.name"))).append("\",\n");
        manifest.append("  \"java\":\"").append(escape(System.getProperty("java.version"))).append("\",\n");
        manifest.append("  \"results\":[");
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) manifest.append(',');
            manifest.append(results.get(i).json());
        }
        manifest.append("],\n  \"productionReady\":false,\n  \"status\":\"").append(failed == 0 ? "passed" : "failed").append("\"\n}\n");
        Files.writeString(output.resolve("manifest.json"), manifest, StandardCharsets.UTF_8);
        System.out.println("zero-platform-production-gate=" + (failed == 0 ? "ok" : "incomplete") + "|mode="
                + mode.name().toLowerCase(Locale.ROOT) + "|output=" + output);
        if (failed > 0) System.exit(1);
    }

    private static Result run(final Path root, final Path output, final String id, final List<String> command,
                              final String marker) throws IOException {
        Instant start = Instant.now();
        Path log = output.resolve(id + ".log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
        inheritJava(builder);
        try {
            Process process = builder.start();
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                reader.lines().forEach(line -> text.append(line).append(System.lineSeparator()));
            }
            boolean done = process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!done) { process.destroyForcibly(); Files.writeString(log, text, StandardCharsets.UTF_8); return new Result(id, "blocked", 124, log, start); }
            Files.writeString(log, text, StandardCharsets.UTF_8);
            String content = text.toString();
            return new Result(id, process.exitValue() == 0 && content.contains(marker) ? "passed" : "failed",
                    process.exitValue(), log, start);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Result(id, "blocked", 130, log, start);
        } catch (IOException ex) {
            Files.writeString(log, ex.toString(), StandardCharsets.UTF_8);
            return new Result(id, "missing", 127, log, start);
        }
    }

    private static void inheritJava(final ProcessBuilder builder) {
        String home = System.getProperty("java.home");
        if (home != null) {
            builder.environment().put("JAVA_HOME", home);
            builder.environment().put("PATH", Path.of(home, "bin") + java.io.File.pathSeparator
                    + builder.environment().getOrDefault("PATH", ""));
        }
    }

    private static String javaCommand() { return Path.of(System.getProperty("java.home"), "bin",
            isWindows() ? "java.exe" : "java").toString(); }
    private static String maven() {
        String configured = System.getenv("ZERO_MAVEN_CMD");
        if (configured != null && !configured.isBlank()) return configured;
        if (isWindows()) return Files.isExecutable(Path.of("mvnw.cmd")) ? "mvnw.cmd" : "mvn.cmd";
        return Files.isExecutable(Path.of("mvnw")) ? "./mvnw" : "mvn";
    }
    private static String powerShell() { return System.getenv().getOrDefault("PWSH", isWindows() ? "pwsh.exe" : "pwsh"); }
    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }
    private static String escape(final String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }

    private enum Mode {
        PLATFORM_TRANSACTION, LOCAL_PRODUCTION_FOCUSED, EXTERNAL_PRODUCTION;
        static Mode parse(final String[] args) {
            if (args.length != 1) throw new IllegalArgumentException("select exactly one gate mode");
            return switch (args[0]) {
                case "--platform-transaction" -> PLATFORM_TRANSACTION;
                case "--local-production-focused" -> LOCAL_PRODUCTION_FOCUSED;
                case "--external-production" -> EXTERNAL_PRODUCTION;
                default -> throw new IllegalArgumentException("unknown gate mode: " + args[0]);
            };
        }
    }

    private record Result(String id, String status, int exitCode, Path log, Instant started) {
        String json() { return "{\"id\":\"" + id + "\",\"status\":\"" + status + "\",\"exitCode\":" + exitCode
                + ",\"log\":\"" + escape(log.toString()) + "\",\"startedAt\":\"" + started + "\"}"; }
    }
}
