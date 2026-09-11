package group.zn.zero.codegen.scaffold;

import group.zn.zero.codegen.scaffold.ProjectScaffoldGenerator.ProjectScaffoldResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
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
        Map<String, String> arguments = ScaffoldArguments.parse(args);
        ScaffoldCatalog catalog = ScaffoldCatalog.standard();
        ScaffoldSelector selector = new ScaffoldSelector(catalog);
        if (arguments.containsKey("--help")) {
            printHelp(catalog);
            return;
        }
        String recommendation = arguments.get("--recommend");
        if (recommendation != null) {
            printRecommendations(catalog, selector, recommendation);
            return;
        }
        String details = arguments.get("--describetemplate");
        if (details != null) {
            printTemplateDetails(catalog.require(details));
            return;
        }
        if (arguments.containsKey("--listtemplates")) {
            printTemplateList(catalog);
            return;
        }
        Options options = Options.parse(arguments);
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
                options.components(),
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
        var selection = new ScaffoldComponents(ScaffoldCatalog.standard().capabilityModel())
                .resolve(request.template().capabilityIds(), request.components());
        System.out.println("  runtimeProfile: " + (selection.external() ? "external-test" : "local"));
        System.out.println("  components: " + String.join(", ", selection.components()));
        System.out.println("  runtimeCapabilities: " + selection.capabilities().size());
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
        System.out.println("  --components <ids>       Additional components, comma-separated: "
                + String.join(",", ScaffoldComponents.supported()));
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

    private record Options(
            String projectName,
            String packageName,
            Path outputDirectory,
            String zeroVersion,
            String template,
            String fromKeywords,
            Path templateRoot,
            List<String> components,
            boolean force) {

        private static Options parse(final Map<String, String> values) {
            String projectName = values.getOrDefault("--projectname", DEFAULT_PROJECT_NAME);
            String packageName = values.getOrDefault("--packagename", DEFAULT_PACKAGE_NAME);
            String output = values.getOrDefault("--outputdir", projectName);
            String fromKeywords = values.getOrDefault("--fromkeywords", "");
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
                    parseComponents(values.getOrDefault("--components", "")),
                    values.containsKey("--force"));
        }

        private static List<String> parseComponents(final String value) {
            if (value.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(value.split(",", -1)).map(String::trim).toList();
        }
    }
}
