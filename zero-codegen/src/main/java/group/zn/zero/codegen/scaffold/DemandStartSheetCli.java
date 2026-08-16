package group.zn.zero.codegen.scaffold;

import group.zn.zero.runtime.capability.MavenCoordinate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 由共享脚手架 catalog 驱动的需求关键词启动单。 */
public final class DemandStartSheetCli {

    private static final String DEFAULT_KEYWORDS = "local rpg";
    private static final String DEFAULT_ZERO_VERSION = "0.1.0-SNAPSHOT";

    private static final Map<String, StackMetadata> LOCAL_STACKS = localStacks();

    private DemandStartSheetCli() {
    }

    /**
     * 输出需求启动单，不生成项目或连接外部系统。
     *
     * @param args 命令行参数。
     */
    public static void main(final String[] args) {
        ScaffoldCatalog catalog = ScaffoldCatalog.standard();
        if (hasFlag(args, "--help", "-h")) {
            printHelp();
            return;
        }
        if (hasFlag(args, "--listStacks", "--list-stacks")) {
            printStacks(catalog);
            return;
        }
        Options options = Options.parse(args, catalog);
        StackSelection selection = select(options, catalog);
        print(options, selection, catalog);
    }

    private static StackSelection select(final Options options, final ScaffoldCatalog catalog) {
        if (!options.stackId().isBlank()) {
            StackMetadata metadata = LOCAL_STACKS.values().stream()
                    .filter(stack -> stack.stackId().equalsIgnoreCase(options.stackId())
                            || stack.templateId().equalsIgnoreCase(options.stackId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown local stack: " + options.stackId()));
            return new StackSelection(metadata, catalog.require(metadata.templateId()));
        }
        ScaffoldTemplate template = new ScaffoldSelector(catalog).select(options.keywords());
        return new StackSelection(LOCAL_STACKS.get(template.id()), template);
    }

    private static void print(
            final Options options,
            final StackSelection selection,
            final ScaffoldCatalog catalog) {
        StackMetadata stack = selection.metadata();
        ScaffoldTemplate template = selection.template();
        List<PathCheck> checks = checks(options.repositoryRoot(), template);
        long warnings = checks.stream().filter(check -> !check.exists()).count();
        System.out.println("zeroServer demand start sheet");
        System.out.println();
        System.out.println("Demand:");
        System.out.println("- selector: " + (options.stackId().isBlank()
                ? "keywords=" + options.keywords() : "stack=" + options.stackId()));
        System.out.println("- selectedStack: " + stack.stackId());
        System.out.println("- title: " + stack.title());
        System.out.println("- status: prototype");
        System.out.println("- reason: " + template.useCase());
        System.out.println();
        System.out.println("Local/prototype path:");
        System.out.println("- command:");
        System.out.println("  " + prototypeCommand(options, template, false));
        System.out.println("- fast structural check:");
        System.out.println("  " + prototypeCommand(options, template, true));
        System.out.println("- business handoff:");
        System.out.println("  read " + quote(options.outputDirectory() + "/BUSINESS_GUIDE.md")
                + " and follow the `First Business Change` recipe for this template.");
        System.out.println();
        System.out.println("Component modules:");
        componentModules(template, catalog).forEach(module -> System.out.println("- " + module));
        System.out.println();
        printVerification(options, stack);
        System.out.println();
        System.out.println("Known gaps:");
        System.out.println("- " + template.productionGap());
        System.out.println();
        System.out.println("Required paths:");
        checks.forEach(check -> System.out.println((check.exists() ? "[PASS] " : "[WARN] ")
                + check.label() + " - " + check.path()));
        System.out.println();
        System.out.println("zero-demand-start-sheet=ok"
                + "|stack=" + stack.stackId()
                + "|status=prototype"
                + "|localCommand=true"
                + "|requiresConfirmation=true"
                + "|warnings=" + warnings);
    }

    private static List<String> componentModules(
            final ScaffoldTemplate template,
            final ScaffoldCatalog catalog) {
        List<String> modules = new ArrayList<>(template.frameworkArtifacts(catalog.capabilityModel()).stream()
                .map(MavenCoordinate::artifactId)
                .toList());
        template.directDependencies().stream()
                .map(dependency -> dependency.coordinate().artifactId())
                .forEach(modules::add);
        return modules.stream().distinct().sorted().toList();
    }

    private static void printVerification(final Options options, final StackMetadata stack) {
        System.out.println("Verification commands:");
        System.out.println("- java scripts/ZeroLocalDoctor.java");
        System.out.println("- java scripts/ZeroArchitectureGuard.java");
        System.out.println("- java scripts/InspectLocalScaffold.java --projectDir "
                + quote(options.outputDirectory()));
        System.out.println("- java scripts/RunLocalScaffold.java --projectDir "
                + quote(options.outputDirectory()));
        System.out.println();
        System.out.println("Production promotion:");
        System.out.println("- java scripts/PlanScaffoldPromotion.java --projectDir "
                + quote(options.outputDirectory()));
        if (!stack.formalGapId().isBlank()) {
            System.out.println("- java scripts/ZeroFrameworkReadiness.java --describeGap " + stack.formalGapId());
        }
    }

    private static List<PathCheck> checks(final Path repositoryRoot, final ScaffoldTemplate template) {
        return List.of(
                check(repositoryRoot, "run-local-prototype", "scripts/RunLocalPrototype.java"),
                check(repositoryRoot, "architecture-guard", "scripts/ZeroArchitectureGuard.java"),
                check(repositoryRoot, "template", "templates/" + template.directory()),
                check(repositoryRoot, "runtime-module", "zero-runtime/pom.xml"),
                check(repositoryRoot, "codegen-module", "zero-codegen/pom.xml"));
    }

    private static PathCheck check(final Path root, final String label, final String relativePath) {
        Path path = root.resolve(relativePath).normalize();
        return new PathCheck(label, path, Files.exists(path));
    }

    private static String prototypeCommand(
            final Options options,
            final ScaffoldTemplate template,
            final boolean structuralOnly) {
        List<String> parts = new ArrayList<>(List.of(
                "java scripts/RunLocalPrototype.java",
                "--template", template.id(),
                "--projectName", options.projectName(),
                "--packageName", options.packageName(),
                "--outputDir", quote(options.outputDirectory()),
                "--zeroVersion", options.zeroVersion()));
        if (structuralOnly) {
            parts.addAll(List.of("--skipInstall", "--skipTests", "--skipRun"));
        }
        return String.join(" ", parts);
    }

    private static void printStacks(final ScaffoldCatalog catalog) {
        System.out.println("zeroServer demand start sheet stacks");
        catalog.templates().forEach(template -> {
            StackMetadata stack = LOCAL_STACKS.get(template.id());
            System.out.println("- id=" + stack.stackId()
                    + "|status=prototype|template=" + template.id()
                    + "|title=" + stack.title());
        });
        System.out.println("zero-demand-start-sheet-stacks=ok|stacks=" + catalog.templates().size());
    }

    private static void printHelp() {
        System.out.println("zeroServer demand start sheet");
        System.out.println("Usage: java scripts/ZeroDemandStartSheet.java --keywords \"open world shard\"");
        System.out.println("  --keywords <words>       Business requirement keywords. Default: " + DEFAULT_KEYWORDS);
        System.out.println("  --stack <id>             Select a known local stack directly.");
        System.out.println("  --projectName <name>     Project name used in printed commands.");
        System.out.println("  --packageName <package>  Java package used in printed commands.");
        System.out.println("  --outputDir <path>       Output directory used in printed commands.");
        System.out.println("  --zeroVersion <version>  zeroServer version used in printed commands.");
        System.out.println("  --listStacks             Print known stack ids.");
    }

    private static Map<String, StackMetadata> localStacks() {
        Map<String, StackMetadata> stacks = new LinkedHashMap<>();
        add(stacks, "local", "local-rpg", "RPG / 普通在线游戏本地闭环", "");
        add(stacks, "room", "room-matchmaking", "房间 / 匹配 / 小局对战", "room-matchmaking");
        add(stacks, "scene-sync", "scene-sync", "RPG 场景同步 / 简单 AOI", "aoi-state-sync");
        add(stacks, "frame-sync", "frame-sync", "帧同步 / lockstep", "frame-sync");
        add(stacks, "npc-tick", "npc-tick-ai", "AI NPC / tick 调度", "npc-tick-ai");
        add(stacks, "ranking-season", "ranking-season", "排行榜 / 赛季", "ranking-season");
        add(stacks, "world-shard", "world-shard", "开放世界 / 分片迁移", "world-shard");
        return Map.copyOf(stacks);
    }

    private static void add(
            final Map<String, StackMetadata> stacks,
            final String templateId,
            final String stackId,
            final String title,
            final String formalGapId) {
        stacks.put(templateId, new StackMetadata(templateId, stackId, title, formalGapId));
    }

    private static boolean hasFlag(final String[] args, final String... names) {
        for (String arg : args) {
            for (String name : names) {
                if (name.equalsIgnoreCase(arg)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String quote(final String value) {
        return value.matches("[A-Za-z0-9_./\\\\:-]+") ? value : '"' + value.replace("\"", "\\\"") + '"';
    }

    private record StackMetadata(String templateId, String stackId, String title, String formalGapId) {
    }

    private record StackSelection(StackMetadata metadata, ScaffoldTemplate template) {
    }

    private record PathCheck(String label, Path path, boolean exists) {
    }

    private record Options(
            String keywords,
            String stackId,
            String projectName,
            String packageName,
            String outputDirectory,
            String zeroVersion,
            Path repositoryRoot) {

        private static Options parse(final String[] args, final ScaffoldCatalog catalog) {
            Map<String, String> values = values(args);
            String keywords = values.getOrDefault("--keywords",
                    values.getOrDefault("--requirement", DEFAULT_KEYWORDS));
            String stackId = values.getOrDefault("--stack",
                    values.getOrDefault("--stackid", values.getOrDefault("--stack-id", "")));
            ScaffoldTemplate defaultTemplate = stackId.isBlank()
                    ? new ScaffoldSelector(catalog).select(keywords)
                    : catalog.require(LOCAL_STACKS.values().stream()
                            .filter(stack -> stack.stackId().equalsIgnoreCase(stackId)
                                    || stack.templateId().equalsIgnoreCase(stackId))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("unknown local stack: " + stackId))
                            .templateId());
            String projectName = values.getOrDefault("--projectname", "my-" + defaultTemplate.id());
            String packageName = values.getOrDefault(
                    "--packagename",
                    "group.zn.zero.generated." + projectName.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT));
            validate(projectName, packageName);
            return new Options(
                    keywords,
                    stackId,
                    projectName,
                    packageName,
                    values.getOrDefault("--outputdir", "target/" + projectName),
                    values.getOrDefault("--zeroversion", DEFAULT_ZERO_VERSION),
                    Path.of(values.getOrDefault("--repositoryroot", ".")).toAbsolutePath().normalize());
        }

        private static Map<String, String> values(final String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < args.length; index++) {
                if (!args[index].startsWith("--") || index + 1 >= args.length || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException("invalid argument: " + args[index]);
                }
                values.put(args[index].toLowerCase(Locale.ROOT), args[++index]);
            }
            return values;
        }

        private static void validate(final String projectName, final String packageName) {
            if (!projectName.matches("[A-Za-z0-9_.-]+")) {
                throw new IllegalArgumentException("projectName contains unsupported characters");
            }
            if (!packageName.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) {
                throw new IllegalArgumentException("packageName must be a valid dotted Java package");
            }
        }
    }
}
