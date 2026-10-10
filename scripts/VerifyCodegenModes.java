import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 独立 JVM 验收当前构件的模式、退出码和手改保护；输出位于隔离目录。 */
public final class VerifyCodegenModes {
    private VerifyCodegenModes() { }

    /** 从仓库根运行；须先构建当前 reactor，任何断言失败均使 CI 非零退出。 */
    public static void main(final String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("no arguments expected");
        Path jar = currentJar();
        Path base = Files.createDirectories(Path.of("target/codegen-modes"));
        Path root = Files.createTempDirectory(base, "run-").toAbsolutePath();
        Path source = root.resolve("Api.si");
        Path output = root.resolve("out");
        Files.writeString(source, "client_to_server:\n@id(101)\nping(int value)\n");
        run(jar, source, output, "validate", 0, "01");
        if (Files.exists(output)) throw new IOException("validate wrote output");
        run(jar, source, output, "check", 3, "02");
        run(jar, source, output, "generate", 0, "03");
        run(jar, source, output, "check", 0, "04");
        Files.writeString(source, "client_to_server:\n@id(101)\nping(long value)\n");
        run(jar, source, output, "check", 3, "05");
        run(jar, source, output, "generate", 0, "06");
        Path dto;
        try (var paths = Files.walk(output)) {
            dto = paths.filter(path -> path.toString().endsWith("DTO.java")).findFirst().orElseThrow();
        }
        String edited = Files.readString(dto) + "\n// user edit\n";
        Files.writeString(dto, edited);
        run(jar, source, output, "validate", 1, "07");
        if (!Files.readString(dto).equals(edited)) throw new IOException("validate overwrote user edit");
        Files.writeString(source, "client_to_server:\n@id(102)\nping(int value)\n");
        run(jar, source, root.resolve("invalid"), "validate", 1, "08");
        System.out.println("codegen-modes=ok|cases=8|evidence=" + root);
    }

    private static Path currentJar() throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var model = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        String version = javax.xml.xpath.XPathFactory.newInstance().newXPath().evaluate("/project/version", model).trim();
        if (!version.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) throw new IOException("invalid reactor version");
        Path jar = Path.of("zero-codegen/target/zero-codegen-" + version + "-all.jar").toAbsolutePath();
        if (!Files.isRegularFile(jar)) throw new IOException("build current codegen artifact first: " + jar);
        return jar;
    }

    private static void run(final Path jar, final Path source, final Path output, final String mode,
                            final int expected, final String id) throws Exception {
        String binary = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", binary);
        Path log = source.getParent().resolve(id + "-" + mode + ".log");
        Process process = new ProcessBuilder(List.of(java.toString(), "-jar", jar.toString(), "--input",
                source.toString(), "--out", output.toString(), "--mode", mode, "--json"))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IOException("codegen timed out: " + log);
            if (process.exitValue() != expected) {
                throw new IOException(mode + " expected " + expected + ", got " + process.exitValue() + ": " + log);
            }
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }
}
