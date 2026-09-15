import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs the local adapter consumer and contract slices with fresh logs and stable markers. */
public final class VerifyAdapterCompositions {
    private static final Path OUTPUT = Path.of("target", "adapter-composition-verify");

    private VerifyAdapterCompositions() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length != 0) {
            throw new IllegalArgumentException("VerifyAdapterCompositions takes no arguments");
        }
        Files.createDirectories(OUTPUT);
        run("discovery-consumer", List.of(maven(), "-B", "-ntp", "-f",
                "examples/modular-composition/discovery/pom.xml", "-Dtest=DiscoveryConsumerTest",
                "-Dsurefire.failIfNoSpecifiedTests=false", "test"), "modular-consumer=ok|profile=discovery");
        run("redis-consumer", List.of(maven(), "-B", "-ntp", "-f",
                "examples/modular-composition/redis/pom.xml", "-Dtest=RedisConsumerTest",
                "-Dsurefire.failIfNoSpecifiedTests=false", "test"), "modular-consumer=ok|profile=redis");
        run("production-adapter-contracts", List.of(maven(), "-B", "-ntp", "-pl",
                "zero-runtime-production,zero-server-starter-production", "-am",
                "-Dtest=ProductionConfigResolverTest,ProductionRuntimeAssemblyContractTest,ProductionNacosProvidersTest",
                "-Dsurefire.failIfNoSpecifiedTests=false", "test"), "BUILD SUCCESS");
        System.out.println("adapter-compositions=ok|slices=3");
    }

    private static void run(final String id, final List<String> command, final String marker) throws IOException, InterruptedException {
        Path log = OUTPUT.resolve(id + ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean completed = process.waitFor(10, TimeUnit.MINUTES);
        Files.writeString(log, output, StandardCharsets.UTF_8);
        if (!completed) {
            process.destroyForcibly();
            throw new IllegalStateException("adapter slice timed out: " + id);
        }
        if (process.exitValue() != 0 || !output.contains(marker)) {
            throw new IllegalStateException("adapter slice failed: " + id + " exit=" + process.exitValue());
        }
    }

    private static String maven() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "mvn.cmd" : "mvn";
    }
}
