package group.zn.zero.codegen.scaffold;

import group.zn.zero.runtime.capability.MavenCoordinate;
import group.zn.zero.runtime.capability.RuntimeCapabilityModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 基于受控模板文件映射生成本地游戏项目。 */
public final class ProjectScaffoldGenerator {

    private final RuntimeCapabilityModel capabilityModel;

    public ProjectScaffoldGenerator(final RuntimeCapabilityModel capabilityModel) {
        this.capabilityModel = Objects.requireNonNull(capabilityModel, "capabilityModel");
    }

    /**
     * 生成项目；force 只覆盖模型明确列出的脚手架文件。
     *
     * @param request 生成请求。
     * @return 生成结果。
     * @throws IOException 模板读取或项目写入失败。
     */
    public ProjectScaffoldResult generate(final ProjectScaffoldRequest request) throws IOException {
        ProjectScaffoldRequest current = Objects.requireNonNull(request, "request");
        Path target = current.outputDirectory();
        if (Files.exists(target) && !current.force() && hasAnyChild(target)) {
            throw new IllegalStateException(
                    "outputDirectory already exists and is not empty; use --force to overwrite scaffold files: "
                            + target);
        }
        Path sourceRoot = current.templateRoot().resolve(current.template().directory()).normalize();
        if (!sourceRoot.startsWith(current.templateRoot()) || !Files.isDirectory(sourceRoot)) {
            throw new IllegalStateException("template directory not found: " + sourceRoot);
        }
        String applicationClass = toPascalName(current.projectName()) + "Application";
        String testClass = applicationClass + "Test";
        String packagePath = current.packageName().replace('.', '/');
        Map<String, String> values = placeholders(current, applicationClass, testClass, packagePath);
        List<FileMapping> mappings = mappings(current.template(), applicationClass, testClass, packagePath);
        Files.createDirectories(target);
        for (FileMapping mapping : mappings) {
            render(sourceRoot, target, mapping, values);
        }
        return new ProjectScaffoldResult(target, current.template().id(), current.projectName(), mappings.size());
    }

    private Map<String, String> placeholders(
            final ProjectScaffoldRequest request,
            final String applicationClass,
            final String testClass,
            final String packagePath) {
        ScaffoldTemplate template = request.template();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("__PROJECT_NAME__", request.projectName());
        values.put("__PACKAGE__", request.packageName());
        values.put("__PACKAGE_PATH__", packagePath);
        values.put("__APP_CLASS__", applicationClass);
        values.put("__TEST_CLASS__", testClass);
        values.put("__ZERO_VERSION__", request.zeroVersion());
        values.put("__TEMPLATE_NAME__", template.id());
        values.put("__TEMPLATE_DESCRIPTION__", template.description());
        values.put("__TEMPLATE_USE_CASE__", template.useCase());
        values.put("__PROTOCOL_FILE__", template.protocolOutput());
        values.put("__SUMMARY_PREFIX__", template.summaryPrefix());
        values.put("__PRODUCTION_GAP__", template.productionGap());
        addJsonPlaceholders(values, request);
        values.put("__FRAMEWORK_DEPENDENCIES_XML__", dependenciesXml(template.directDependencies()));
        values.put("__FRAMEWORK_COMPONENTS_JSON__", frameworkComponentsJson(template));
        values.put("__RUNTIME_CAPABILITIES_JSON__", jsonLines(template.capabilityIds()));
        return values;
    }

    private void addJsonPlaceholders(
            final Map<String, String> values,
            final ProjectScaffoldRequest request) {
        ScaffoldTemplate template = request.template();
        values.put("__PROJECT_NAME_JSON__", jsonString(request.projectName()));
        values.put("__PACKAGE_JSON__", jsonString(request.packageName()));
        values.put("__ZERO_VERSION_JSON__", jsonString(request.zeroVersion()));
        values.put("__TEMPLATE_NAME_JSON__", jsonString(template.id()));
        values.put("__TEMPLATE_DESCRIPTION_JSON__", jsonString(template.description()));
        values.put("__TEMPLATE_USE_CASE_JSON__", jsonString(template.useCase()));
        values.put("__PROTOCOL_FILE_JSON__", jsonString(template.protocolOutput()));
        values.put("__SUMMARY_PREFIX_JSON__", jsonString(template.summaryPrefix()));
        values.put("__PRODUCTION_GAP_JSON__", jsonString(template.productionGap()));
    }

    private String frameworkComponentsJson(final ScaffoldTemplate template) {
        List<String> artifactIds = new ArrayList<>(template.frameworkArtifacts(capabilityModel).stream()
                .map(MavenCoordinate::artifactId)
                .toList());
        template.directDependencies().stream()
                .map(dependency -> dependency.coordinate().artifactId())
                .forEach(artifactIds::add);
        return jsonLines(artifactIds.stream().distinct().sorted().toList());
    }

    private String dependenciesXml(final List<ScaffoldDependency> dependencies) {
        List<String> rendered = new ArrayList<>();
        for (ScaffoldDependency dependency : dependencies) {
            MavenCoordinate coordinate = dependency.coordinate();
            StringBuilder xml = new StringBuilder();
            xml.append("        <dependency>\n")
                    .append("            <groupId>").append(coordinate.groupId()).append("</groupId>\n")
                    .append("            <artifactId>").append(coordinate.artifactId()).append("</artifactId>\n")
                    .append("            <version>${zero.version}</version>");
            if (!dependency.scope().isEmpty()) {
                xml.append("\n            <scope>").append(dependency.scope()).append("</scope>");
            }
            xml.append("\n        </dependency>");
            rendered.add(xml.toString());
        }
        return String.join("\n", rendered);
    }

    private String jsonLines(final List<String> values) {
        return values.stream().map(value -> "    " + jsonString(value)).collect(java.util.stream.Collectors.joining(",\n"));
    }

    private List<FileMapping> mappings(
            final ScaffoldTemplate template,
            final String applicationClass,
            final String testClass,
            final String packagePath) {
        return List.of(
                new FileMapping("pom.xml.tpl", "pom.xml"),
                new FileMapping(template.protocolTemplate(), template.protocolOutput()),
                new FileMapping("protoId.txt.tpl", "src/main/protocol/protoId.txt"),
                new FileMapping("Application.java.tpl",
                        "src/main/java/" + packagePath + '/' + applicationClass + ".java"),
                new FileMapping("ApplicationTest.java.tpl",
                        "src/test/java/" + packagePath + '/' + testClass + ".java"),
                new FileMapping("README.md.tpl", "README.md"),
                new FileMapping("BUSINESS_GUIDE.md.tpl", "BUSINESS_GUIDE.md"),
                new FileMapping("COMPONENTS.md.tpl", "COMPONENTS.md"),
                new FileMapping("NEXT_STEPS.md.tpl", "NEXT_STEPS.md"),
                new FileMapping("zero-scaffold.json.tpl", "zero-scaffold.json"));
    }

    private void render(
            final Path sourceRoot,
            final Path targetRoot,
            final FileMapping mapping,
            final Map<String, String> values) throws IOException {
        Path template = sourceRoot.resolve(mapping.template()).normalize();
        Path output = targetRoot.resolve(mapping.output()).normalize();
        if (!template.startsWith(sourceRoot) || !output.startsWith(targetRoot)) {
            throw new IllegalStateException("scaffold mapping escapes its root");
        }
        String content = Files.readString(template, StandardCharsets.UTF_8);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            content = content.replace(entry.getKey(), entry.getValue());
        }
        Files.createDirectories(Objects.requireNonNull(output.getParent(), "output.parent"));
        Files.writeString(output, content, StandardCharsets.UTF_8);
    }

    private boolean hasAnyChild(final Path path) throws IOException {
        try (var stream = Files.list(path)) {
            return stream.findAny().isPresent();
        }
    }

    private String toPascalName(final String value) {
        StringBuilder builder = new StringBuilder(value.length());
        boolean upperNext = true;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!Character.isLetterOrDigit(current)) {
                upperNext = true;
            } else {
                builder.append(upperNext ? Character.toUpperCase(current) : current);
                upperNext = false;
            }
        }
        if (builder.isEmpty()) {
            throw new IllegalArgumentException("projectName must contain at least one letter or digit");
        }
        return builder.toString();
    }

    private String jsonString(final String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2).append('"');
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
                default -> appendJsonCharacter(builder, current);
            }
        }
        return builder.append('"').toString();
    }

    private void appendJsonCharacter(final StringBuilder builder, final char value) {
        if (value < 0x20) {
            builder.append(String.format(Locale.ROOT, "\\u%04x", (int) value));
        } else {
            builder.append(value);
        }
    }

    private record FileMapping(String template, String output) {
    }

    /** @param outputDirectory 生成目录。 @param templateId 模板 ID。 @param projectName 项目名。 @param fileCount 文件数。 */
    public record ProjectScaffoldResult(
            Path outputDirectory,
            String templateId,
            String projectName,
            int fileCount) {
    }
}
