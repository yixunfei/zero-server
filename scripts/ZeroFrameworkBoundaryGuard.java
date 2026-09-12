import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Read-only guard for the framework/template boundary.
 * It does not claim that the repository contains no domain-shaped examples;
 * it verifies that protected framework and scaffold surfaces do not contain
 * project-specific gameplay packages or production claims.
 */
public final class ZeroFrameworkBoundaryGuard {

    private static final List<Path> PROTECTED_SOURCE_ROOTS = List.of(
            Path.of("zero-core", "src", "main"),
            Path.of("zero-runtime", "src", "main"),
            Path.of("zero-server-starter", "src", "main"),
            Path.of("zero-server-starter-production", "src", "main"),
            Path.of("templates"));

    private static final Set<String> FORBIDDEN_GAMEPLAY_TOKENS = Set.of(
            "battle", "combat", "quest", "monetization", "lootbox", "guildwar", "pay_to_win");

    private static final Pattern GAMEPLAY_PACKAGE = Pattern.compile(
            "(?i)(package|import)\\s+[^;]*(?:\\.)(battle|combat|quest|monetization|lootbox|guildwar|pay_to_win)(?:\\.|;)");

    private ZeroFrameworkBoundaryGuard() {
    }

    public static void main(final String[] args) throws IOException {
        if (hasFlag(args, "--help", "-h")) {
            System.out.println("Usage: java scripts/ZeroFrameworkBoundaryGuard.java");
            System.out.println("Checks protected framework and scaffold surfaces only.");
            return;
        }
        List<String> failures = new ArrayList<>();
        checkRepository(failures);
        checkProtectedSources(failures);
        checkTemplates(failures);
        checkNoExampleDependency(failures);
        if (failures.isEmpty()) {
            System.out.println("zero-framework-boundary-guard=ok"
                    + "|protectedRoots=" + PROTECTED_SOURCE_ROOTS.size()
                    + "|gameplayTokens=" + FORBIDDEN_GAMEPLAY_TOKENS.size()
                    + "|productionClaims=false");
            return;
        }
        for (String failure : failures) {
            System.out.println("[FAIL] " + failure);
        }
        System.out.println("zero-framework-boundary-guard=failed|violations=" + failures.size());
        System.exit(1);
    }

    private static void checkRepository(final List<String> failures) {
        if (!Files.isRegularFile(Path.of("pom.xml")) || !Files.isDirectory(Path.of("templates"))) {
            failures.add("run from repository root");
        }
    }

    private static void checkProtectedSources(final List<String> failures) throws IOException {
        for (Path root : PROTECTED_SOURCE_ROOTS) {
            if (!Files.exists(root)) {
                failures.add("missing protected root: " + root);
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(Files::isRegularFile)
                        .filter(ZeroFrameworkBoundaryGuard::isTextSource)
                        .forEach(path -> inspectSource(path, failures));
            }
        }
    }

    private static void inspectSource(final Path path, final List<String> failures) {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            String lower = text.toLowerCase(Locale.ROOT);
            for (String token : FORBIDDEN_GAMEPLAY_TOKENS) {
                if (GAMEPLAY_PACKAGE.matcher(text).find()) {
                    failures.add("project gameplay package in protected source: " + path);
                    return;
                }
            }
        } catch (IOException ex) {
            failures.add("cannot read protected source: " + path);
        }
    }

    private static void checkTemplates(final List<String> failures) throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("templates"))) {
            files.filter(path -> path.getFileName().toString().equals("zero-scaffold.json.tpl"))
                    .forEach(path -> {
                        try {
                            String text = Files.readString(path, StandardCharsets.UTF_8);
                            if (!text.contains("prototype") || !text.contains("true")
                                    || !text.contains("connectsExternalMiddleware")) {
                                failures.add("template must declare prototype=true and external middleware metadata: " + path);
                            }
                        } catch (IOException ex) {
                            failures.add("cannot read scaffold manifest: " + path);
                        }
                    });
        }
    }

    private static void checkNoExampleDependency(final List<String> failures) throws IOException {
        for (String module : List.of("zero-core", "zero-runtime", "zero-server-starter",
                "zero-server-starter-production")) {
            Path pom = Path.of(module, "pom.xml");
            if (!Files.isRegularFile(pom)) {
                failures.add("missing protected pom: " + pom);
                continue;
            }
            String text = Files.readString(pom, StandardCharsets.UTF_8);
            if (text.contains("<artifactId>examples") || (text.contains("<artifactId>zero-logic</artifactId>")
                    && !text.contains("<scope>test</scope>"))) {
                failures.add("protected module depends on example/logic artifact: " + module);
            }
        }
    }

    private static boolean isTextSource(final Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".java") || name.endsWith(".xml") || name.endsWith(".json")
                || name.endsWith(".tpl") || name.endsWith(".md");
    }

    private static boolean hasFlag(final String[] args, final String... flags) {
        for (String arg : args) {
            for (String flag : flags) {
                if (flag.equalsIgnoreCase(arg)) {
                    return true;
                }
            }
        }
        return false;
    }
}
