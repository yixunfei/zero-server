import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Verifies only the generated mixed local composition and its dependency metadata. */
public final class VerifyMixedComposition {
    private static final Path OUTPUT = Path.of("target", "mixed-composition-verify");

    private VerifyMixedComposition() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length != 0) {
            throw new IllegalArgumentException("VerifyMixedComposition takes no arguments");
        }
        Files.createDirectories(OUTPUT);
        Path log = OUTPUT.resolve("verify.log");
        Process process = new ProcessBuilder(javaCommand(), "scripts/VerifyGeneratedCompositions.java")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean completed = process.waitFor(10, TimeUnit.MINUTES);
        Files.writeString(log, output, StandardCharsets.UTF_8);
        if (!completed) {
            process.destroyForcibly();
            throw new IllegalStateException("mixed composition verifier timed out");
        }
        if (process.exitValue() != 0 || !output.contains("generated-consumer=ok|profile=mixed")
                || !output.contains("generated-compositions=ok")) {
            throw new IllegalStateException("mixed composition verification failed: exit=" + process.exitValue());
        }
        System.out.println("mixed-composition=ok|consumer=mixed");
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java")
                .toString();
    }
}
