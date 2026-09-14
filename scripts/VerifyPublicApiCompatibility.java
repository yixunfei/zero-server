import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Generates and verifies a tracked public/protected API surface manifest. */
public final class VerifyPublicApiCompatibility {
    private static final List<String> MODULES = List.of(
            "zero-core", "zero-runtime", "zero-protocol", "zero-rpc-common", "zero-data");
    private static final Path BASELINE = Path.of("docs", "api-baseline", "0.1.0-SNAPSHOT");

    private VerifyPublicApiCompatibility() {
    }

    public static void main(final String[] args) throws Exception {
        boolean generate = has(args, "--generate");
        boolean check = has(args, "--check") || !generate;
        Files.createDirectories(BASELINE);
        int failures = 0;
        for (String module : MODULES) {
            Path current = manifest(module);
            if (generate) {
                Files.write(current, surface(module));
                System.out.println("api-baseline-generated=" + module + "|path=" + current);
            }
            if (check) {
                Path baseline = BASELINE.resolve(module + ".api");
                if (!Files.isRegularFile(baseline)) {
                    System.out.println("api-compatibility-failure=" + module + "|reason=baseline-missing");
                    failures++;
                    continue;
                }
                List<String> oldSymbols = Files.readAllLines(baseline);
                List<String> currentSymbols = surface(module);
                Set<String> currentSet = Set.copyOf(currentSymbols);
                for (String symbol : oldSymbols) {
                    if (!currentSet.contains(symbol)) {
                        System.out.println("api-compatibility-failure=" + module + "|removed=" + symbol);
                        failures++;
                    }
                }
                System.out.println("api-compatibility=" + module + "|baseline="
                        + oldSymbols.size() + "|current=" + currentSymbols.size());
            }
        }
        if (failures > 0) {
            throw new IllegalStateException("API compatibility failures=" + failures);
        }
        System.out.println("api-compatibility-gate=ok|modules=" + MODULES.size()
                + "|baseline=0.1.0-SNAPSHOT|allowAdditive=true");
    }

    private static Path manifest(final String module) {
        return BASELINE.resolve(module + ".api");
    }

    private static List<String> surface(final String module) throws IOException {
        Path classes = Path.of(module, "target", "classes");
        if (!Files.isDirectory(classes)) {
            throw new IllegalStateException("compiled classes missing for " + module);
        }
        List<String> result = new ArrayList<>();
        try (var stream = Files.walk(classes)) {
            stream.filter(path -> path.toString().endsWith(".class"))
                    .map(classes::relativize)
                    .map(path -> path.toString().replace('\\', '.').replace('/', '.'))
                    .map(name -> name.substring(0, name.length() - ".class".length()))
                    .filter(name -> !name.equals("module-info"))
                    .forEach(name -> inspectClass(module, name, result));
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    private static void inspectClass(
            final String module, final String className, final List<String> output) {
        try {
            Process process = new ProcessBuilder(
                    "javap", "-classpath", Path.of(module, "target", "classes").toString(),
                    "-public", className).redirectErrorStream(true).start();
            String text = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException("javap failed for " + module + ":" + className);
            }
            for (String line : text.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("public ") || trimmed.startsWith("protected ")) {
                    output.add(trimmed);
                }
            }
        } catch (IOException | InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cannot inspect " + module + ":" + className, exception);
        }
    }

    private static boolean has(final String[] args, final String flag) {
        return Arrays.asList(args).contains(flag);
    }
}
