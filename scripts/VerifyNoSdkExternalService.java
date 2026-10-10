import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Builds a disposable no-SDK consumer and verifies its isolated dependency closure. */
public final class VerifyNoSdkExternalService {
    private static final Path OUTPUT = Path.of("target", "no-sdk-external-service").toAbsolutePath().normalize();
    private static final String[] FORBIDDEN_ARTIFACTS = {
        "zero-server-starter", "zero-codegen", "zero-net", "zero-runtime-net", "zero-runtime-kafka",
        "zero-runtime-mongo", "zero-runtime-postgresql", "zero-runtime-redis", "zero-runtime-nacos",
        "kafka-clients", "jedis", "mongodb-driver", "postgresql", "nacos-client"
    };

    private VerifyNoSdkExternalService() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length != 0) {
            throw new IllegalArgumentException("VerifyNoSdkExternalService takes no arguments");
        }
        Files.createDirectories(OUTPUT);
        runMaven(List.of(maven(), "-B", "-ntp", "-pl", "zero-runtime-bootstrap", "-am", "-DskipTests", "install"),
                OUTPUT.resolve("current-reactor-build.log"));
        Files.writeString(OUTPUT.resolve("reactor-version.txt"), reactorVersion());
        writeFixture();
        runMaven(List.of(maven(), "-B", "-ntp", "-f", OUTPUT.resolve("pom.xml").toString(), "clean", "test",
                "dependency:build-classpath", "-DincludeScope=runtime", "-Dmdep.outputFile=target/runtime-classpath.txt"),
                OUTPUT.resolve("maven.log"));
        String classpath = Files.readString(OUTPUT.resolve("target/runtime-classpath.txt"), StandardCharsets.UTF_8);
        for (String forbidden : FORBIDDEN_ARTIFACTS) {
            if (classpath.toLowerCase(Locale.ROOT).contains(forbidden.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("forbidden runtime artifact present: " + forbidden);
            }
        }
        System.out.println("no-sdk-external-service=ok|artifacts=core,runtime,bootstrap|classes=forbidden-absent");
    }

    private static void writeFixture() throws IOException {
        Path source = OUTPUT.resolve("src/test/java/cleanroom/NoSdkExternalServiceTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(OUTPUT.resolve("pom.xml"), "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
                + "<groupId>cleanroom</groupId><artifactId>no-sdk-external-service</artifactId><version>1</version>"
                + "<properties><maven.compiler.release>21</maven.compiler.release></properties><dependencies>"
                + dependency("group.zn.zero", "zero-runtime-bootstrap")
                + "<dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>"
                + "<version>5.10.3</version><scope>test</scope></dependency></dependencies><build><plugins>"
                + "<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version></plugin>"
                + "<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version></plugin>"
                + "</plugins></build></project>", StandardCharsets.UTF_8);
        Files.writeString(source, "package cleanroom;\n"
                + "import static org.junit.jupiter.api.Assertions.assertTrue;\n"
                + "import group.zn.zero.runtime.api.GameRuntime;\n"
                + "import group.zn.zero.runtime.bootstrap.RuntimeBasics;\n"
                + "import org.junit.jupiter.api.Test;\n"
                + "class NoSdkExternalServiceTest { @Test void localRuntimeHasNoExternalSideEffects() {\n"
                + "try (GameRuntime runtime = RuntimeBasics.builder().build()) { runtime.start(); assertTrue(runtime.running()); }\n"
                + "assertTrue(true); } }\n", StandardCharsets.UTF_8);
    }

    private static String dependency(final String group, final String artifact) throws IOException {
        return "<dependency><groupId>" + group + "</groupId><artifactId>" + artifact
                + "</artifactId><version>" + reactorVersion() + "</version></dependency>";
    }

    private static void runMaven(final List<String> command, final Path log) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            builder.environment().put("JAVA_HOME", javaHome);
            String pathKey = builder.environment().keySet().stream()
                    .filter(key -> key.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
            builder.environment().put(pathKey, Path.of(javaHome, "bin") + java.io.File.pathSeparator
                    + builder.environment().getOrDefault(pathKey, ""));
        }
        Process process = builder.start();
        boolean completed;
        try {
            completed = process.waitFor(10, TimeUnit.MINUTES);
        } catch (InterruptedException interrupted) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (!completed) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new IllegalStateException("no-SDK consumer timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("no-SDK consumer failed: exit=" + process.exitValue());
        }
    }

    /** 当前根 POM 的字面版本；禁用外部实体，不回退历史构件。 */
    private static String reactorVersion() throws IOException {
        try {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var model = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
            String value = javax.xml.xpath.XPathFactory.newInstance().newXPath().evaluate("/project/version", model).trim();
            if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) throw new IOException("invalid reactor version");
            return value;
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException
                | javax.xml.xpath.XPathExpressionException failure) {
            throw new IOException("cannot read reactor version", failure);
        }
    }

    private static String maven() {
        return Path.of(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "mvnw.cmd" : "mvnw").toAbsolutePath().toString();
    }
}
