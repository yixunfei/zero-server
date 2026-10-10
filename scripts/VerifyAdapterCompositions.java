import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs the local adapter consumer and contract slices with fresh logs and stable markers. */
public final class VerifyAdapterCompositions {
    private static final Path OUTPUT = Path.of("target", "adapter-composition-verify", java.util.UUID.randomUUID().toString());

    private VerifyAdapterCompositions() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length != 0) {
            throw new IllegalArgumentException("VerifyAdapterCompositions takes no arguments");
        }
        Files.createDirectories(OUTPUT);
        run("current-reactor-build", List.of(maven(), "-B", "-ntp", "-pl",
                "zero-server-starter-production", "-am", "-DskipTests", "install"), "BUILD SUCCESS", List.of());
        run("discovery-consumer", List.of(maven(), "-B", "-ntp", "-f",
                "examples/modular-composition/discovery/pom.xml", "-Dtest=DiscoveryConsumerTest",
                "-Dsurefire.failIfNoSpecifiedTests=true", "test"), "modular-consumer=ok|profile=discovery", List.of(new Expected("examples/modular-composition/discovery", "DiscoveryConsumerTest")));
        run("redis-consumer", List.of(maven(), "-B", "-ntp", "-f",
                "examples/modular-composition/redis/pom.xml", "-Dtest=RedisConsumerTest",
                "-Dsurefire.failIfNoSpecifiedTests=true", "test"), "modular-consumer=ok|profile=redis", List.of(new Expected("examples/modular-composition/redis", "RedisConsumerTest")));
        run("production-adapter-contracts", List.of(maven(), "-B", "-ntp", "-pl",
                "zero-runtime-production,zero-server-starter-production", "-am",
                "-Dtest=ProductionConfigResolverTest,ProductionRuntimeAssemblyContractTest,ProductionNacosProvidersTest",
                "-Dsurefire.failIfNoSpecifiedTests=false", "test"), "BUILD SUCCESS", List.of(new Expected("zero-runtime-production", "ProductionConfigResolverTest"),
                new Expected("zero-server-starter-production", "ProductionRuntimeAssemblyContractTest"),
                new Expected("zero-server-starter-production", "ProductionNacosProvidersTest")));
        System.out.println("adapter-compositions=ok|slices=3");
    }

    private static void run(final String id, final List<String> command, final String marker,
                            final List<Expected> expected) throws Exception {
        String suffix = java.util.UUID.randomUUID().toString();
        List<String> actual = new java.util.ArrayList<>(command);
        actual.add("-Dsurefire.reportNameSuffix=" + suffix);
        Path log = OUTPUT.resolve(id + ".log");
        ProcessBuilder builder = new ProcessBuilder(actual).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process process = builder.start();
        try {
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new IOException("adapter slice timed out: " + log);
            }
        } catch (InterruptedException interrupted) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        // Maven/Windows 控制台可能混用本地编码；标记固定为 ASCII，逐字节保留，原始日志不转写。
        if (process.exitValue() != 0 || !Files.readString(log, StandardCharsets.ISO_8859_1).contains(marker)) {
            throw new IOException("adapter slice failed: " + log + " exit=" + process.exitValue());
        }
        Path evidence = Files.createDirectories(OUTPUT.resolve(id + "-reports"));
        for (Expected item : expected) {
            Path reportRoot = Path.of(item.module(), "target", "surefire-reports");
            List<Path> matches;
            try (var files = Files.list(reportRoot)) {
                matches = files.filter(path -> path.getFileName().toString().endsWith("." + item.test() + "-" + suffix + ".xml"))
                        .toList();
            }
            if (matches.size() != 1) throw new IOException("missing fresh report: " + item.test());
            Path report = Files.copy(matches.getFirst(), evidence.resolve(matches.getFirst().getFileName()));
            verifyReport(report, item.test(), suffix);
        }
    }

    /** XML 必须来自本次 UUID，并证明目标类至少一个测试实际运行且全绿。 */
    static void verifyReport(final Path report, final String test, final String suffix) throws Exception {
        if (!report.getFileName().toString().endsWith("." + test + "-" + suffix + ".xml")) {
            throw new IOException("stale or mismatched report: " + report);
        }
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var suite = factory.newDocumentBuilder().parse(report.toFile()).getDocumentElement();
        String name = suite.getAttribute("name");
        if (!(name.endsWith("." + test) || name.endsWith("." + test + "(" + suffix + ")"))) {
            throw new IOException("wrong test suite: " + name);
        }
        int tests = Integer.parseInt(suite.getAttribute("tests"));
        int skipped = Integer.parseInt(suite.getAttribute("skipped"));
        if (tests <= skipped || skipped < 0 || Integer.parseInt(suite.getAttribute("failures")) != 0
                || Integer.parseInt(suite.getAttribute("errors")) != 0
                || suite.getElementsByTagName("testcase").getLength() != tests
                || suite.getElementsByTagName("skipped").getLength() != skipped
                || suite.getElementsByTagName("failure").getLength() != 0
                || suite.getElementsByTagName("error").getLength() != 0) {
            throw new IOException("test slice has no passing coverage: " + test);
        }
    }

    private static String maven() {
        return Path.of(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "mvnw.cmd" : "mvnw").toAbsolutePath().toString();
    }

    /** 模块和必须执行的测试类。 */
    private record Expected(String module, String test) { }
}
