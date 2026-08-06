import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * zeroServer local game scaffold generator.
 *
 * @author zn
 */
public final class NewLocalGame {

    /**
     * Default project name.
     */
    private static final String DEFAULT_PROJECT_NAME = "zero-local-game";

    /**
     * Default Java package.
     */
    private static final String DEFAULT_PACKAGE_NAME = "group.zn.zero.localgame";

    /**
     * Default zeroServer version.
     */
    private static final String DEFAULT_ZERO_VERSION = "0.1.0-SNAPSHOT";

    private NewLocalGame() {
    }

    /**
     * Generates a local game scaffold.
     *
     * @param args command line arguments.
     * @throws IOException when template reading or scaffold writing fails.
     */
    public static void main(final String[] args) throws IOException {
        if (hasFlag(args, "--help", "-h")) {
            printHelp();
            return;
        }
        String recommendQuery = optionValue(args, "--recommend", "--recommendTemplate", "--recommend-template");
        if (recommendQuery != null) {
            printRecommendations(recommendQuery);
            return;
        }
        String templateDetails = optionValue(args, "--describeTemplate", "--describe-template");
        if (templateDetails != null) {
            printTemplateDetails(TemplateKind.parse(templateDetails));
            return;
        }
        if (hasFlag(args, "--listTemplates", "--list-templates")) {
            printTemplateList();
            return;
        }

        Options options = Options.parse(args);
        validate(options);

        Path target = options.outputDir().toAbsolutePath().normalize();
        if (Files.exists(target) && !options.force() && hasAnyChild(target)) {
            throw new IllegalStateException(
                    "OutputDir already exists and is not empty. Use --force to overwrite known scaffold files: "
                            + target);
        }

        String appClass = toPascalName(options.projectName()) + "Application";
        String testClass = appClass + "Test";
        String packagePath = options.packageName().replace('.', '/');
        TemplateKind templateKind = TemplateKind.parse(options.template());
        Path templateRoot = Path.of("templates", templateKind.directory()).toAbsolutePath().normalize();
        if (!Files.isDirectory(templateRoot)) {
            throw new IllegalStateException("Template directory not found: " + templateRoot);
        }

        Map<String, String> values = new LinkedHashMap<>();
        values.put("__PROJECT_NAME__", options.projectName());
        values.put("__PACKAGE__", options.packageName());
        values.put("__PACKAGE_PATH__", packagePath);
        values.put("__APP_CLASS__", appClass);
        values.put("__TEST_CLASS__", testClass);
        values.put("__ZERO_VERSION__", options.zeroVersion());
        values.put("__TEMPLATE_NAME__", templateKind.optionName());
        values.put("__TEMPLATE_DESCRIPTION__", templateKind.description());
        values.put("__TEMPLATE_USE_CASE__", templateKind.useCase());
        values.put("__PROTOCOL_FILE__", templateKind.protocolOutput());
        values.put("__SUMMARY_PREFIX__", templateKind.summaryPrefix());
        values.put("__PRODUCTION_GAP__", templateKind.productionGap());
        values.put("__PROJECT_NAME_JSON__", jsonString(options.projectName()));
        values.put("__PACKAGE_JSON__", jsonString(options.packageName()));
        values.put("__ZERO_VERSION_JSON__", jsonString(options.zeroVersion()));
        values.put("__TEMPLATE_NAME_JSON__", jsonString(templateKind.optionName()));
        values.put("__TEMPLATE_DESCRIPTION_JSON__", jsonString(templateKind.description()));
        values.put("__TEMPLATE_USE_CASE_JSON__", jsonString(templateKind.useCase()));
        values.put("__PROTOCOL_FILE_JSON__", jsonString(templateKind.protocolOutput()));
        values.put("__SUMMARY_PREFIX_JSON__", jsonString(templateKind.summaryPrefix()));
        values.put("__PRODUCTION_GAP_JSON__", jsonString(templateKind.productionGap()));

        List<FileMapping> mappings = templateKind.mappings(
                new FileMapping("pom.xml.tpl", "pom.xml"),
                new FileMapping("protoId.txt.tpl", "src/main/protocol/protoId.txt"),
                new FileMapping("Application.java.tpl", "src/main/java/" + packagePath + "/" + appClass + ".java"),
                new FileMapping(
                        "ApplicationTest.java.tpl",
                        "src/test/java/" + packagePath + "/" + testClass + ".java"),
                new FileMapping("README.md.tpl", "README.md"),
                new FileMapping("BUSINESS_GUIDE.md.tpl", "BUSINESS_GUIDE.md"),
                new FileMapping("COMPONENTS.md.tpl", "COMPONENTS.md"),
                new FileMapping("NEXT_STEPS.md.tpl", "NEXT_STEPS.md"),
                new FileMapping("zero-scaffold.json.tpl", "zero-scaffold.json"));

        Files.createDirectories(target);
        for (FileMapping mapping : mappings) {
            Path template = templateRoot.resolve(mapping.template());
            Path output = target.resolve(mapping.output());
            String content = Files.readString(template, StandardCharsets.UTF_8);
            for (Map.Entry<String, String> entry : values.entrySet()) {
                content = content.replace(entry.getKey(), entry.getValue());
            }
            Files.createDirectories(output.getParent());
            Files.writeString(output, content, StandardCharsets.UTF_8);
        }

        System.out.println("Created zeroServer local game scaffold:");
        System.out.println("  path: " + target);
        System.out.println("  template: " + templateKind.optionName());
        if (!options.fromKeywords().isBlank()) {
            System.out.println("  selectedFromKeywords: " + options.fromKeywords());
        }
        System.out.println("  project: " + options.projectName());
        System.out.println("  package: " + options.packageName());
        System.out.println("Next:");
        System.out.println("  mvn -q -f \"" + target.resolve("pom.xml") + "\" clean test");
        System.out.println("  mvn -q -f \"" + target.resolve("pom.xml") + "\" exec:java");
        System.out.println("  read \"" + target.resolve("BUSINESS_GUIDE.md") + "\" for business editing points");
        System.out.println("  read \"" + target.resolve("COMPONENTS.md") + "\" for component boundaries");
        System.out.println("  read \"" + target.resolve("NEXT_STEPS.md") + "\" for production promotion gaps");
        System.out.println("  inspect \"" + target.resolve("zero-scaffold.json") + "\" for scaffold metadata");
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

    private static String optionValue(final String[] args, final String... names) {
        for (int index = 0; index < args.length; index++) {
            for (String name : names) {
                if (name.equalsIgnoreCase(args[index])) {
                    if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                        throw new IllegalArgumentException("missing value for argument: " + args[index]);
                    }
                    return args[index + 1];
                }
            }
        }
        return null;
    }

    private static void printHelp() {
        System.out.println("zeroServer local game scaffold generator");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java scripts/NewLocalGame.java [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --projectName <name>      Project artifact name. Default: " + DEFAULT_PROJECT_NAME);
        System.out.println("  --packageName <package>   Java package. Default: " + DEFAULT_PACKAGE_NAME);
        System.out.println("  --outputDir <path>        Output directory. Default: projectName");
        System.out.println("  --zeroVersion <version>   zeroServer dependency version. Default: " + DEFAULT_ZERO_VERSION);
        System.out.println("  --template <template>     Scaffold template. Default: " + TemplateKind.LOCAL.optionName());
        System.out.println("  --fromKeywords <words>    Select scaffold template from business keywords");
        System.out.println("  --force                   Overwrite known scaffold files in outputDir");
        System.out.println("  --listTemplates           Print supported templates");
        System.out.println("  --recommend <keywords>    Recommend templates by business keywords");
        System.out.println("  --describeTemplate <name> Print detailed template information");
        System.out.println("  --help, -h                Print this help");
        System.out.println();
        printTemplateList();
        System.out.println();
        System.out.println("Template catalog:");
        System.out.println("  docs/scaffold-templates.zh-CN.md");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java scripts/NewLocalGame.java --projectName my-local-game "
                + "--packageName group.zn.zero.generated.mylocal --outputDir target/my-local-game");
        System.out.println("  java scripts/NewLocalGame.java --template frame-sync --projectName my-frame-sync "
                + "--packageName group.zn.zero.generated.myframe --outputDir target/my-frame-sync");
        System.out.println("  java scripts/NewLocalGame.java --fromKeywords \"open world shard\" "
                + "--projectName my-world-game --packageName group.zn.zero.generated.myworld "
                + "--outputDir target/my-world-game");
        System.out.println("  java scripts/NewLocalGame.java --recommend \"aoi scene sync\"");
        System.out.println("  java scripts/NewLocalGame.java --describeTemplate world-shard");
    }

    private static void printTemplateList() {
        System.out.println("Templates:");
        for (TemplateKind kind : TemplateKind.values()) {
            System.out.println("  " + kind.optionName() + " - " + kind.description());
        }
    }

    private static void printRecommendations(final String query) {
        if (query.isBlank()) {
            throw new IllegalArgumentException("recommend keywords must not be blank");
        }
        List<TemplateScore> scores = new ArrayList<>();
        for (TemplateKind kind : TemplateKind.values()) {
            int score = kind.recommendationScore(query);
            if (score > 0) {
                scores.add(new TemplateScore(kind, score));
            }
        }
        scores.sort(Comparator
                .comparingInt(TemplateScore::score)
                .reversed()
                .thenComparing(score -> score.kind().optionName()));

        System.out.println("Template recommendations for: " + query);
        if (scores.isEmpty()) {
            System.out.println("  No direct keyword match. Read the full catalog or start with local.");
            System.out.println();
            printTemplateList();
            System.out.println();
            System.out.println("Suggested command:");
            printGenerateCommand(TemplateKind.LOCAL);
            return;
        }

        int limit = Math.min(3, scores.size());
        for (int index = 0; index < limit; index++) {
            TemplateScore score = scores.get(index);
            TemplateKind kind = score.kind();
            System.out.println("  " + kind.optionName() + " score=" + score.score()
                    + " - " + kind.description());
            System.out.println("    use: " + kind.useCase());
            System.out.println("    summary: " + kind.summaryPrefix());
            System.out.println("    gap: " + kind.productionGap());
        }
        System.out.println();
        System.out.println("Suggested command:");
        printGenerateCommand(scores.getFirst().kind());
        System.out.println();
        System.out.println("Full catalog: docs/scaffold-templates.zh-CN.md");
    }

    private static void printTemplateDetails(final TemplateKind kind) {
        System.out.println("Template: " + kind.optionName());
        System.out.println("Description: " + kind.description());
        System.out.println("Use case: " + kind.useCase());
        System.out.println("Protocol file: " + kind.protocolOutput());
        System.out.println("Summary prefix: " + kind.summaryPrefix());
        System.out.println("Production gap: " + kind.productionGap());
        System.out.println("Keywords: " + String.join(", ", kind.keywords()));
        System.out.println();
        System.out.println("Generate:");
        printGenerateCommand(kind);
        System.out.println();
        System.out.println("Full catalog: docs/scaffold-templates.zh-CN.md");
    }

    private static TemplateKind recommendTemplate(final String query) {
        if (query.isBlank()) {
            throw new IllegalArgumentException("fromKeywords must not be blank");
        }
        List<TemplateScore> scores = new ArrayList<>();
        for (TemplateKind kind : TemplateKind.values()) {
            int score = kind.recommendationScore(query);
            if (score > 0) {
                scores.add(new TemplateScore(kind, score));
            }
        }
        scores.sort(Comparator
                .comparingInt(TemplateScore::score)
                .reversed()
                .thenComparing(score -> score.kind().optionName()));
        return scores.isEmpty() ? TemplateKind.LOCAL : scores.getFirst().kind();
    }

    private static void printGenerateCommand(final TemplateKind kind) {
        System.out.println("  java scripts/NewLocalGame.java --template " + kind.optionName()
                + " --projectName my-" + kind.optionName()
                + " --packageName group.zn.zero.generated.my"
                + kind.optionName().replace("-", "")
                + " --outputDir target/my-" + kind.optionName());
    }

    private static void validate(final Options options) {
        if (options.projectName().isBlank()) {
            throw new IllegalArgumentException("projectName must not be blank");
        }
        if (!options.projectName().matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("projectName contains unsupported characters");
        }
        if (!options.packageName().matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) {
            throw new IllegalArgumentException("packageName must be a valid dotted Java package");
        }
        if (options.zeroVersion().isBlank()) {
            throw new IllegalArgumentException("zeroVersion must not be blank");
        }
    }

    private static boolean hasAnyChild(final Path path) throws IOException {
        try (var stream = Files.list(path)) {
            return stream.findAny().isPresent();
        }
    }

    private static String toPascalName(final String value) {
        StringBuilder builder = new StringBuilder(value.length());
        boolean upperNext = true;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!Character.isLetterOrDigit(current)) {
                upperNext = true;
                continue;
            }
            builder.append(upperNext ? Character.toUpperCase(current) : current);
            upperNext = false;
        }
        if (builder.isEmpty()) {
            throw new IllegalArgumentException("projectName must contain at least one letter or digit");
        }
        return builder.toString();
    }

    private static String jsonString(final String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2);
        builder.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (current < 0x20) {
                        builder.append(String.format(Locale.ROOT, "\\u%04x", (int) current));
                    } else {
                        builder.append(current);
                    }
                }
            }
        }
        builder.append('"');
        return builder.toString();
    }

    /**
     * Template file mapping.
     *
     * @param template template file name.
     * @param output generated output path.
     */
    private record FileMapping(String template, String output) {
    }

    /**
     * Template recommendation score.
     *
     * @param kind template kind.
     * @param score keyword score.
     */
    private record TemplateScore(TemplateKind kind, int score) {
    }

    /**
     * Scaffold options.
     *
     * @param projectName project name.
     * @param packageName Java package name.
     * @param outputDir output directory.
     * @param zeroVersion zeroServer dependency version.
     * @param template scaffold template name.
     * @param fromKeywords business keywords used to select template.
     * @param force whether to overwrite known scaffold files.
     */
    private record Options(
            String projectName,
            String packageName,
            Path outputDir,
            String zeroVersion,
            String template,
            String fromKeywords,
            boolean force) {

        private static Options parse(final String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            boolean force = false;
            for (int index = 0; index < args.length; index++) {
                String key = args[index];
                if ("--force".equals(key)) {
                    force = true;
                    continue;
                }
                if (!key.startsWith("--")) {
                    throw new IllegalArgumentException("unsupported argument: " + key);
                }
                if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException("missing value for argument: " + key);
                }
                values.put(key.toLowerCase(Locale.ROOT), args[++index]);
            }
            String projectName = values.getOrDefault("--projectname", DEFAULT_PROJECT_NAME);
            String packageName = values.getOrDefault("--packagename", DEFAULT_PACKAGE_NAME);
            String output = values.getOrDefault("--outputdir", projectName);
            String zeroVersion = values.getOrDefault("--zeroversion", DEFAULT_ZERO_VERSION);
            String fromKeywords = values.getOrDefault("--fromkeywords",
                    values.getOrDefault("--from-keywords", ""));
            if (!fromKeywords.isBlank() && values.containsKey("--template")) {
                throw new IllegalArgumentException("--template and --fromKeywords cannot be used together");
            }
            String template = fromKeywords.isBlank()
                    ? values.getOrDefault("--template", TemplateKind.LOCAL.optionName())
                    : recommendTemplate(fromKeywords).optionName();
            return new Options(projectName, packageName, Path.of(output), zeroVersion, template, fromKeywords, force);
        }
    }

    /**
     * Supported scaffold template.
     */
    private enum TemplateKind {

        /**
         * Local generated protocol player / scene template.
         */
        LOCAL(
                "local",
                "local-game-scaffold",
                "Game.si.tpl",
                "src/main/protocol/Game.si",
                "player and scene local prototype",
                List.of("local", "rpg", "player", "scene", "login", "move", "prototype", "本地", "玩家", "场景"),
                "RPG or common online game local prototype with login, player and scene flow",
                "local-game=ok",
                "auth, reconnect, durable online state and production persistence"),

        /**
         * Local room / match template.
         */
        ROOM(
                "room",
                "room-game-scaffold",
                "Room.si.tpl",
                "src/main/protocol/Room.si",
                "room and match local prototype",
                List.of("room", "match", "battle", "pvp", "ready", "frame", "房间", "匹配", "对战", "小局"),
                "room, match, ready and small battle state serialized by actor lane",
                "room-game=ok",
                "matchmaking, broadcast, reconnect, spectator, settlement and cross-server room"),

        /**
         * Local scene state sync template.
         */
        SCENE_SYNC(
                "scene-sync",
                "scene-sync-scaffold",
                "SceneSync.si.tpl",
                "src/main/protocol/SceneSync.si",
                "scene state sync and simple AOI local prototype",
                List.of("scene", "sync", "aoi", "visibility", "entity", "rpg", "mmo", "场景", "同步", "可见性"),
                "scene entity state sync and small-scale AOI visibility query",
                "scene-sync=ok",
                "production AOI index, broadcast, delta compression, snapshot protocol and migration"),

        /**
         * Local frame sync template.
         */
        FRAME_SYNC(
                "frame-sync",
                "frame-sync-scaffold",
                "FrameSync.si.tpl",
                "src/main/protocol/FrameSync.si",
                "frame sync lockstep local prototype",
                List.of("frame", "lockstep", "input", "snapshot", "deterministic", "帧同步", "输入", "快照"),
                "lockstep match input collection, frame advance and snapshot summary",
                "frame-sync=ok",
                "clock model, input recovery, rollback, spectator, reliable broadcast and anti-cheat"),

        /**
         * Local NPC tick template.
         */
        NPC_TICK(
                "npc-tick",
                "npc-tick-scaffold",
                "NpcTick.si.tpl",
                "src/main/protocol/NpcTick.si",
                "AI NPC lifecycle and tick local prototype",
                List.of("npc", "ai", "tick", "zone", "behavior", "spawn", "怪物", "行为", "调度"),
                "NPC spawn, behavior switch, zone tick and NPC state query",
                "npc-tick=ok",
                "behavior tree, pathfinding, combat AI, tick budget, backpressure and migration"),

        /**
         * Local ranking and season template.
         */
        RANKING_SEASON(
                "ranking-season",
                "ranking-season-scaffold",
                "RankingSeason.si.tpl",
                "src/main/protocol/RankingSeason.si",
                "ranking and season local prototype",
                List.of("ranking", "rank", "season", "score", "top", "leaderboard", "排行", "赛季", "积分"),
                "ranking score submit, top query, player rank query and season reset",
                "ranking-season=ok",
                "Redis sorted set, cross-server ranking, settlement, reward and idempotency"),

        /**
         * Local world shard and migration template.
         */
        WORLD_SHARD(
                "world-shard",
                "world-shard-scaffold",
                "WorldShard.si.tpl",
                "src/main/protocol/WorldShard.si",
                "open world shard and migration local prototype",
                List.of("world", "shard", "openworld", "migration", "transfer", "partition", "开放世界", "分片", "迁移"),
                "open world shard state, entity movement, shard transfer and entity query",
                "world-shard=ok",
                "cross-process migration, state handoff, cross-server broadcast, AOI stitching and consistency");

        /**
         * Command line option value.
         */
        private final String optionName;

        /**
         * Template directory name.
         */
        private final String directory;

        /**
         * Protocol template file name.
         */
        private final String protocolTemplate;

        /**
         * Protocol output path.
         */
        private final String protocolOutput;

        /**
         * Human-readable template description.
         */
        private final String description;

        /**
         * Recommendation keywords.
         */
        private final List<String> keywords;

        /**
         * Recommended use case.
         */
        private final String useCase;

        /**
         * Expected run summary prefix.
         */
        private final String summaryPrefix;

        /**
         * Production gap description.
         */
        private final String productionGap;

        TemplateKind(
                final String optionName,
                final String directory,
                final String protocolTemplate,
                final String protocolOutput,
                final String description,
                final List<String> keywords,
                final String useCase,
                final String summaryPrefix,
                final String productionGap) {
            this.optionName = optionName;
            this.directory = directory;
            this.protocolTemplate = protocolTemplate;
            this.protocolOutput = protocolOutput;
            this.description = description;
            this.keywords = List.copyOf(keywords);
            this.useCase = useCase;
            this.summaryPrefix = summaryPrefix;
            this.productionGap = productionGap;
        }

        private String optionName() {
            return optionName;
        }

        private String directory() {
            return directory;
        }

        private String description() {
            return description;
        }

        private String protocolOutput() {
            return protocolOutput;
        }

        private List<String> keywords() {
            return keywords;
        }

        private String useCase() {
            return useCase;
        }

        private String summaryPrefix() {
            return summaryPrefix;
        }

        private String productionGap() {
            return productionGap;
        }

        private int recommendationScore(final String query) {
            String normalized = normalize(query);
            int score = 0;
            if (normalized.contains(normalize(optionName))) {
                score += 100;
            }
            if (normalized.contains(normalize(description))) {
                score += 20;
            }
            for (String keyword : keywords) {
                if (normalized.contains(normalize(keyword))) {
                    score += 10;
                }
            }
            return score;
        }

        private List<FileMapping> mappings(final FileMapping pom, final FileMapping protoId,
                final FileMapping application, final FileMapping test, final FileMapping readme,
                final FileMapping businessGuide, final FileMapping components, final FileMapping nextSteps,
                final FileMapping manifest) {
            return List.of(
                    pom,
                    new FileMapping(protocolTemplate, protocolOutput),
                    protoId,
                    application,
                    test,
                    readme,
                    businessGuide,
                    components,
                    nextSteps,
                    manifest);
        }

        private static TemplateKind parse(final String value) {
            for (TemplateKind kind : values()) {
                if (kind.optionName.equalsIgnoreCase(value)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException(
                    "unsupported template: " + value + " (supported: " + supportedNames() + ")");
        }

        private static String supportedNames() {
            StringBuilder builder = new StringBuilder();
            for (TemplateKind kind : values()) {
                if (!builder.isEmpty()) {
                    builder.append(", ");
                }
                builder.append(kind.optionName);
            }
            return builder.toString();
        }

        private static String normalize(final String value) {
            return value.toLowerCase(Locale.ROOT)
                    .replace("_", "-")
                    .replace(" ", "-");
        }
    }
}
