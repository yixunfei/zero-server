import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Compiles a small consumer against installed core contract artifacts. */
public final class VerifyApiCompatibilityConsumer {
    private VerifyApiCompatibilityConsumer() {
    }

    public static void main(final String[] args) throws Exception {
        Path root = Path.of("target", "api-compatibility-consumer");
        Path source = root.resolve("src/Consumer.java");
        Path classes = root.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, """
                import group.zn.zero.core.spi.ZeroProvider;
                import group.zn.zero.protocol.ProtocolFrame;
                import group.zn.zero.data.DataService;
                public final class Consumer {
                    public static void main(String[] args) {
                        if (ZeroProvider.class == null || ProtocolFrame.class == null || DataService.class == null) {
                            throw new IllegalStateException("contract missing");
                        }
                    }
                }
                """);
        String separator = System.getProperty("path.separator");
        String classpath = List.of(
                "zero-core/target/classes",
                "zero-runtime/target/classes",
                "zero-protocol/target/classes",
                "zero-rpc-common/target/classes",
                "zero-data/target/classes").stream().reduce((left, right) -> left + separator + right).orElseThrow();
        Process process = new ProcessBuilder("javac", "-source", "21", "-target", "21",
                "-cp", classpath, "-d", classes.toString(), source.toString())
                .redirectErrorStream(true).inheritIO().start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("consumer compilation failed");
        }
        System.out.println("api-compatibility-consumer=ok|modules=5|java=21");
    }
}
