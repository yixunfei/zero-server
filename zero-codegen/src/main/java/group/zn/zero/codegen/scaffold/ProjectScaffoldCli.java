package group.zn.zero.codegen.scaffold;

import group.zn.zero.codegen.scaffold.ProjectScaffoldGenerator.ProjectScaffoldResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** zero-codegen 项目脚手架命令行入口。 */
public final class ProjectScaffoldCli {

    private static final String DEFAULT_PROJECT_NAME = "zero-local-game";
    private static final String DEFAULT_PACKAGE_NAME = "group.zn.zero.localgame";
    private static final String DEFAULT_ZERO_VERSION = "0.1.0-SNAPSHOT";

    private ProjectScaffoldCli() {
    }

    /**
     * 选择并生成本地游戏项目。
     *
     * @param args 命令行参数。
     * @throws IOException 模板读取或项目写入失败。
     */
    public static void main(final String[] args) throws IOException {
        ScaffoldCatalog catalog = ScaffoldCatalog.standard();
        ScaffoldSelector selector = new ScaffoldSelector(catalog);
        if (hasFlag(args, "--help", "-h")) {
            printHelp(catalog);
            return;
        }
        String recommendation = optionValue(args, "--recommend", "--recommendTemplate", "--recommend-template");
        if (recommendation != null) {
            printRecommendations(catalog, selector, recommendation);
            return;
        }
        String details = optionValue(args, "--describeTemplate", "--describe-template");
        if (details != null) {
            printTemplateDetails(catalog.require(details));
            return;
        }
        if (hasFlag(args, "--listTemplates", "--list-templates")) {
            printTemplateList(catalog);
            return;
        }
        Options options = Options.parse(args);
        ScaffoldTemplate template = options.fromKeywords().isBlank()
                ? catalog.require(options.template())
                : selector.select(options.fromKeywords());
        ProjectScaffoldRequest request = new ProjectScaffoldRequest(
                options.projectName(),
                options.packageName(),
                options.outputDirectory(),
                options.zeroVersion(),
                template,
                options.templateRoot(),
                options.fromKeywords(),
                options.force());
        ProjectScaffoldResult result = new ProjectScaffoldGenerator(catalog.capabilityModel()).generate(request);
        printResult(request, result);
    }

    private static void printResult(
            final ProjectScaffoldRequest request,
            final ProjectScaffoldResult result) {
        Path target = result.outputDirectory();
        System.out.println("Created zeroServer local game scaffold:");
        System.out.println("  path: " + target);
        System.out.println("  template: " + result.templateId());
        if (!request.selectedFromKeywords().isBlank()) {
            System.out.println("  selectedFromKeywords: " + request.selectedFromKeywords());
        }
        System.out.println("  project: " + result.projectName());
        System.out.println("  package: " + request.packageName());
        System.out.println("  runtimeProfile: local");
        System.out.println("  runtimeCapabilities: " + request.template().capabilityIds().size());
        System.out.println("Next:");
        System.out.println("  mvn -q -f \"" + target.resolve("pom.xml") + "\" clean test");
        System.out.println("  mvn -q -f \"" + target.resolve("pom.xml") + "\" exec:java");
        System.out.println("  read \"" + target.resolve("BUSINESS_GUIDE.md") + "\" for business editing points");
        System.out.println("  read \"" + target.resolve("COMPONENTS.md") + "\" for component boundaries");
        System.out.println("  read \"" + target.resolve("NEXT_STEPS.md") + "\" for production promotion gaps");
        System.out.println("  inspect \"" + target.resolve("zero-scaffold.json") + "\" for scaffold metadata");
    }

    private static void printRecommendations(
            final ScaffoldCatalog catalog,
            final ScaffoldSelector selector,
            final String query) {
        List<ScaffoldSelector.Recommendation> recommendations = selector.recommendations(query);
        System.out.println("Template recommendations for: " + query);
        if (recommendations.isEmpty()) {
            System.out.println("  No direct keyword match. Read the full catalog or start with local.");
            System.out.println();
            printTemplateList(catalog);
            System.out.println();
            System.out.println("Suggested command:");
            printGenerateCommand(catalog.require("local"));
            return;
        }
        recommendations.stream().limit(3).forEach(recommendation -> {
            ScaffoldTemplate template = recommendation.template();
            System.out.println("  " + template.id() + " score=" + recommendation.score()
                    + " - " + template.description());
            System.out.println("    use: " + template.useCase());
            System.out.println("    summary: " + template.summaryPrefix());
            System.out.println("    gap: " + template.productionGap());
        });
        System.out.println();
        System.out.println("Suggested command:");
        printGenerateCommand(recommendations.getFirst().template());
        System.out.println();
        System.out.println("Full catalog: docs/scaffold-templates.zh-CN.md");
    }

    private static void printTemplateDetails(final ScaffoldTemplate template) {
        System.out.println("Template: " + template.id());
        System.out.println("Description: " + template.description());
        System.out.println("Use case: " + template.useCase());
        System.out.println("Protocol file: " + template.protocolOutput());
        System.out.println("Summary prefix: " + template.summaryPrefix());
        System.out.println("Production gap: " + template.productionGap());
        System.out.println("Keywords: " + String.join(", ", template.keywords()));
        System.out.println("Runtime capabilities: " + String.join(", ", template.capabilityIds()));
        System.out.println();
        System.out.println("Generate:");
        printGenerateCommand(template);
        System.out.println();
        System.out.println("Full catalog: docs/scaffold-templates.zh-CN.md");
    }

    private static void printTemplateList(final ScaffoldCatalog catalog) {
        System.out.println("Templates:");
        catalog.templates().forEach(template ->
                System.out.println("  " + template.id() + " - " + template.description()));
    }

    private static void printGenerateCommand(final ScaffoldTemplate template) {
        System.out.println("  java scripts/NewLocalGame.java --template " + template.id()
                + " --projectName my-" + template.id()
                + " --packageName group.zn.zero.generated.my" + template.id().replace("-", "")
                + " --outputDir target/my-" + template.id());
    }

    private static void printHelp(final ScaffoldCatalog catalog) {
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
        System.out.println("  --template <template>     Scaffold template. Default: local");
        System.out.println("  --fromKeywords <words>    Select scaffold template from business keywords");
        System.out.println("  --force                   Overwrite known scaffold files in outputDir");
        System.out.println("  --listTemplates           Print supported templates");
        System.out.println("  --recommend <keywords>    Recommend templates by business keywords");
        System.out.println("  --describeTemplate <name> Print detailed template information");
        System.out.println("  --help, -h                Print this help");
        System.out.println();
        printTemplateList(catalog);
        System.out.println();
        System.out.println("Template catalog: docs/scaffold-templates.zh-CN.md");
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

    private record Options(
            String projectName,
            String packageName,
            Path outputDirectory,
            String zeroVersion,
            String template,
            String fromKeywords,
            Path templateRoot,
            boolean force) {

        private static Options parse(final String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            boolean force = false;
            for (int index = 0; index < args.length; index++) {
                String key = args[index];
                if ("--force".equalsIgnoreCase(key)) {
                    force = true;
                } else {
                    if (!key.startsWith("--") || index + 1 >= args.length || args[index + 1].startsWith("--")) {
                        throw new IllegalArgumentException("invalid argument: " + key);
                    }
                    values.put(key.toLowerCase(Locale.ROOT), args[++index]);
                }
            }
            String projectName = values.getOrDefault("--projectname", DEFAULT_PROJECT_NAME);
            String packageName = values.getOrDefault("--packagename", DEFAULT_PACKAGE_NAME);
            String output = values.getOrDefault("--outputdir", projectName);
            String fromKeywords = values.getOrDefault("--fromkeywords",
                    values.getOrDefault("--from-keywords", ""));
            if (!fromKeywords.isBlank() && values.containsKey("--template")) {
                throw new IllegalArgumentException("--template and --fromKeywords cannot be used together");
            }
            return new Options(
                    projectName,
                    packageName,
                    Path.of(output),
                    values.getOrDefault("--zeroversion", DEFAULT_ZERO_VERSION),
                    values.getOrDefault("--template", "local"),
                    fromKeywords,
                    Path.of(values.getOrDefault("--templateroot", "templates")),
                    force);
        }
    }
}
