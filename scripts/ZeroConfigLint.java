import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static lint for generated zeroServer scaffold metadata and example properties.
 * It never loads runtime classes or starts providers, threads, listeners, or clients.
 */
public final class ZeroConfigLint {
    private static final Pattern JSON_FIELD = Pattern.compile("\"([A-Za-z][A-Za-z0-9]*)\"\\s*:");
    private static final Pattern PROPERTY = Pattern.compile("^([A-Za-z][A-Za-z0-9_.-]*)\\s*=\\s*(.*)$");
    private static final Set<String> REQUIRED_FIELDS = Set.of("schemaVersion", "ownershipSchemaVersion", "generator",
            "projectName", "packageName", "zeroVersion", "template", "prototype", "connectsExternalMiddleware",
            "opensNetworkPorts", "runtimeProfile", "requiresExternalServices", "selectedComponents",
            "selectedProviders", "runtimeCapabilities", "frameworkComponents");
    private static final Set<String> SENSITIVE_TOKENS = Set.of("password", "passwd", "secret", "token", "key");

    private ZeroConfigLint() {
    }

    public static void main(final String[] args) {
        try {
            Options options = Options.parse(args);
            List<String> errors = lint(options.projectDir());
            if (errors.isEmpty()) {
                System.out.println("zero-config-lint=ok|project=" + options.projectDir());
                return;
            }
            errors.forEach(error -> System.err.println(error));
            System.exit(1);
        } catch (MissingInputException exception) {
            System.err.println("ZERO-CONFIG-LINT-MISSING|" + exception.getMessage());
            System.exit(3);
        } catch (IllegalArgumentException exception) {
            System.err.println("ZERO-CONFIG-LINT-INVALID-ARGUMENT|" + exception.getMessage());
            System.exit(2);
        } catch (IOException exception) {
            System.err.println("ZERO-CONFIG-LINT-READ-FAILED|" + safe(exception.getMessage()));
            System.exit(1);
        }
    }

    private static List<String> lint(final Path projectDir) throws IOException {
        if (!Files.isDirectory(projectDir)) {
            throw new MissingInputException("project directory is missing: " + projectDir);
        }
        Path metadata = projectDir.resolve("zero-scaffold.json");
        if (!Files.isRegularFile(metadata)) {
            throw new MissingInputException("zero-scaffold.json is missing: " + metadata);
        }
        String json = Files.readString(metadata, StandardCharsets.UTF_8);
        List<String> errors = new ArrayList<>();
        Set<String> fields = fields(json);
        REQUIRED_FIELDS.stream().filter(field -> !fields.contains(field))
                .forEach(field -> errors.add("ZERO-CONFIG-LINT-MISSING-FIELD|field=" + field));
        if (!json.contains("\"schemaVersion\": 1") && !json.contains("\"schemaVersion\":1")) {
            errors.add("ZERO-CONFIG-LINT-SCHEMA-VERSION|expected=1");
        }
        if (!json.contains("\"ownershipSchemaVersion\": 1") && !json.contains("\"ownershipSchemaVersion\":1")) {
            errors.add("ZERO-CONFIG-LINT-OWNERSHIP-VERSION|expected=1");
        }
        boolean external = booleanField(json, "connectsExternalMiddleware");
        if (external != booleanField(json, "requiresExternalServices")) {
            errors.add("ZERO-CONFIG-LINT-FLAGS-INCONSISTENT|connectsExternalMiddleware/requiresExternalServices");
        }
        if (json.contains("\"opensNetworkPorts\": true") || json.contains("\"opensNetworkPorts\":true")) {
            errors.add("ZERO-CONFIG-LINT-SIDE-EFFECT|opensNetworkPorts=true is not allowed in scaffold metadata");
        }
        Path properties = projectDir.resolve("config/application.properties.example");
        if (Files.exists(properties)) {
            lintProperties(properties, errors);
        }
        return errors;
    }

    private static void lintProperties(final Path file, final List<String> errors) throws IOException {
        Set<String> keys = new HashSet<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNumber++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            Matcher matcher = PROPERTY.matcher(trimmed);
            if (!matcher.matches()) {
                errors.add("ZERO-CONFIG-LINT-INVALID-PROPERTY|line=" + lineNumber);
                continue;
            }
            String key = matcher.group(1);
            String value = matcher.group(2).trim();
            if (!keys.add(key)) {
                errors.add("ZERO-CONFIG-LINT-DUPLICATE-KEY|key=" + key);
            }
            String lower = key.toLowerCase(java.util.Locale.ROOT);
            if (SENSITIVE_TOKENS.stream().anyMatch(lower::contains) && !value.isEmpty()
                    && !value.startsWith("<") && !value.equals("CHANGE_ME")) {
                errors.add("ZERO-CONFIG-LINT-SENSITIVE-VALUE|key=" + key + "|value=<redacted>");
            }
        }
    }

    private static Set<String> fields(final String json) {
        Set<String> fields = new HashSet<>();
        Matcher matcher = JSON_FIELD.matcher(json);
        while (matcher.find()) {
            fields.add(matcher.group(1));
        }
        return fields;
    }

    private static boolean booleanField(final String json, final String name) {
        return json.contains("\"" + name + "\": true") || json.contains("\"" + name + "\":true");
    }

    private static String safe(final String value) {
        return value == null ? "" : value.replaceAll("(?i)(password|token|secret|key)=\\S+", "$1=<redacted>");
    }

    private record Options(Path projectDir) {
        static Options parse(final String[] args) {
            Path project = Path.of("target", "acceptance-evidence", "config-lint-fixture").toAbsolutePath().normalize();
            for (int i = 0; i < args.length; i++) {
                if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                    System.out.println("Usage: java scripts/ZeroConfigLint.java [--projectDir <path>]");
                    System.exit(0);
                }
                if (!"--projectDir".equals(args[i]) || ++i >= args.length) {
                    throw new IllegalArgumentException("expected --projectDir <path>");
                }
                project = Path.of(args[i]).toAbsolutePath().normalize();
            }
            return new Options(project);
        }
    }

    private static final class MissingInputException extends RuntimeException {
        MissingInputException(final String message) {
            super(message);
        }
    }
}
