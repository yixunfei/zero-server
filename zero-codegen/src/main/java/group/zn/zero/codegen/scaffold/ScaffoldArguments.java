package group.zn.zero.codegen.scaffold;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Canonical option parsing shared by generation and catalog commands. */
final class ScaffoldArguments {
    private static final Set<String> FLAGS = Set.of("--help", "--force", "--listtemplates");
    private static final Set<String> VALUES = Set.of("--projectname", "--packagename", "--outputdir",
            "--zeroversion", "--template", "--components", "--fromkeywords", "--templateroot",
            "--recommend", "--describetemplate");
    private static final Map<String, String> ALIASES = Map.of(
            "-h", "--help", "--list-templates", "--listtemplates",
            "--from-keywords", "--fromkeywords", "--recommendtemplate", "--recommend",
            "--recommend-template", "--recommend", "--describe-template", "--describetemplate");

    private ScaffoldArguments() { }

    static Map<String, String> parse(final String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index++) {
            String input = args[index].toLowerCase(Locale.ROOT);
            String key = ALIASES.getOrDefault(input, input);
            if (!FLAGS.contains(key) && !VALUES.contains(key)) {
                throw new IllegalArgumentException("unknown argument: " + args[index]);
            }
            if (values.containsKey(key)) {
                throw new IllegalArgumentException("duplicate argument: " + args[index]);
            }
            String value = "true";
            if (VALUES.contains(key)) {
                if (index + 1 >= args.length || args[index + 1].startsWith("--") || args[index + 1].equals("-h")) {
                    throw new IllegalArgumentException("missing value for argument: " + args[index]);
                }
                value = args[++index];
            }
            values.put(key, value);
        }
        long commands = Set.of("--help", "--listtemplates", "--recommend", "--describetemplate").stream()
                .filter(values::containsKey).count();
        if (commands > 1) {
            throw new IllegalArgumentException("select only one catalog command");
        }
        return Map.copyOf(values);
    }
}
