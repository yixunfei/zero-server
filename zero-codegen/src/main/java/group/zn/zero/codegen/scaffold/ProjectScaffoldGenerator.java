package group.zn.zero.codegen.scaffold;

import group.zn.zero.runtime.capability.MavenCoordinate;
import group.zn.zero.runtime.capability.RuntimeCapabilityModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
        String applicationClass = ScaffoldJavaNames.applicationClass(current.projectName());
        String testClass = applicationClass + "Test";
        String serverClass = applicationClass + "Server";
        String clientClass = applicationClass + "TcpClient";
        String packagePath = current.packageName().replace('.', '/');
        var selection = new ScaffoldComponents(capabilityModel).resolve(current.template().capabilityIds(), current.components());
        Map<String, String> values = placeholders(current, applicationClass, testClass, serverClass, clientClass, packagePath, selection);
        List<FileMapping> mappings = mappings(current.template(), applicationClass, testClass, serverClass, clientClass,
                packagePath, sourceRoot, selection);
        Files.createDirectories(target);
        for (FileMapping mapping : mappings) {
            render(sourceRoot, target, mapping, values);
        }
        var assembly = new ScaffoldAssemblyRenderer();
        String assemblyOutput = "src/main/java/" + packagePath + "/RuntimeAssembly.java";
        String configOutput = "config/application.properties.example";
        String assemblyContent = assembly.render(current, selection);
        String configContent = assembly.configExample(current, selection);
        write(target, assemblyOutput, assemblyContent);
        write(target, configOutput, configContent);
        writeOwnershipManifest(target, mappings, assemblyOutput, configOutput, current, values);
        return new ProjectScaffoldResult(target, current.template().id(), current.projectName(), mappings.size() + 2);
    }

    private Map<String, String> placeholders(
            final ProjectScaffoldRequest request,
            final String applicationClass,
            final String testClass,
            final String serverClass,
            final String clientClass,
            final String packagePath,
            final ScaffoldComponents.Selection selection) {
        ScaffoldTemplate template = request.template();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("__PROJECT_NAME__", request.projectName());
        values.put("__PACKAGE__", request.packageName());
        values.put("__PACKAGE_PATH__", packagePath);
        values.put("__APP_CLASS__", applicationClass);
        values.put("__SERVER_CLASS__", serverClass);
        values.put("__CLIENT_CLASS__", clientClass);
        values.put("__TEST_CLASS__", testClass);
        values.put("__ZERO_VERSION__", request.zeroVersion());
        values.put("__TEMPLATE_NAME__", template.id());
        values.put("__TEMPLATE_DESCRIPTION__", template.description());
        values.put("__TEMPLATE_USE_CASE__", template.useCase());
        values.put("__PROTOCOL_FILE__", template.protocolOutput());
        values.put("__SUMMARY_PREFIX__", template.summaryPrefix());
        values.put("__PRODUCTION_GAP__", template.productionGap());
        addJsonPlaceholders(values, request);
        List<ScaffoldDependency> dependencies = new ArrayList<>(template.directDependencies());
        for (MavenCoordinate coordinate : selection.artifacts()) {
            dependencies.add(new ScaffoldDependency(coordinate, ""));
        }
        dependencies = dependencies.stream().distinct().sorted().toList();
        values.put("__FRAMEWORK_DEPENDENCIES_XML__", dependenciesXml(dependencies));
        values.put("__FRAMEWORK_COMPONENTS_JSON__", jsonLines(dependencies.stream()
                .map(dependency -> dependency.coordinate().artifactId()).distinct().sorted().toList()));
        values.put("__RUNTIME_CAPABILITIES_JSON__", jsonLines(selection.capabilities()));
        values.put("__SELECTED_COMPONENTS_JSON__", jsonLines(selection.components()));
        values.put("__SELECTED_PROVIDERS_JSON__", jsonLines(selection.providers()));
        values.put("__SELECTED_COMPONENTS__", String.join(", ", selection.components()));
        values.put("__RUNTIME_PROFILE__", selection.external() ? "external-test" : "local");
        values.put("__EXTERNAL_COMPONENTS__", Boolean.toString(selection.external()));
        values.put("__DEFAULT_START__", Boolean.toString(!selection.external()));
        values.put("__CONFIG_DEFAULTS__", ScaffoldConfiguration.defaults(selection));
        values.put("__DIAGNOSIS_STATUS__", selection.external()
                ? "report.missingConfigKeys().isEmpty() ? \"ok\" : \"incomplete\"" : "\"ok\"");
        values.put("__DIAGNOSIS_KEYS__", selection.external() ? "report.missingConfigKeys()" : "java.util.List.of()");
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
            final String serverClass,
            final String clientClass,
            final String packagePath,
            final Path sourceRoot,
            final ScaffoldComponents.Selection selection) {
        List<FileMapping> result = new ArrayList<>(List.of(
                new FileMapping("pom.xml.tpl", "pom.xml"),
                new FileMapping("Application.java.tpl",
                        "src/main/java/" + packagePath + '/' + applicationClass + ".java"),
                new FileMapping("ApplicationTest.java.tpl",
                        "src/test/java/" + packagePath + '/' + testClass + ".java"),
                new FileMapping("README.md.tpl", "README.md"),
                new FileMapping("BUSINESS_GUIDE.md.tpl", "BUSINESS_GUIDE.md"),
                new FileMapping("COMPONENTS.md.tpl", "COMPONENTS.md"),
                new FileMapping("NEXT_STEPS.md.tpl", "NEXT_STEPS.md"),
                new FileMapping("zero-scaffold.json.tpl", "zero-scaffold.json")));
        if ("local".equals(template.id())) {
            addIfPresent(result, sourceRoot, "LocalGameBO.java.tpl", "src/main/java/" + packagePath + "/LocalGameBO.java");
            addIfPresent(result, sourceRoot, "LocalGameObservation.java.tpl", "src/main/java/" + packagePath + "/LocalGameObservation.java");
            addIfPresent(result, sourceRoot, "LocalGameFixture.java.tpl", "src/main/java/" + packagePath + "/LocalGameFixture.java");
            addIfPresent(result, sourceRoot, "LocalGameFlow.java.tpl", "src/main/java/" + packagePath + "/LocalGameFlow.java");
            addIfPresent(result, sourceRoot, "LocalGameAsyncTest.java.tpl", "src/test/java/" + packagePath + "/LocalGameAsyncTest.java");
            if (selection.components().contains("net")) {
                addIfPresent(result, sourceRoot, "LocalGameServer.java.tpl",
                        "src/main/java/" + packagePath + '/' + serverClass + ".java");
                addIfPresent(result, sourceRoot, "LocalGameTcpClient.java.tpl",
                        "src/main/java/" + packagePath + '/' + clientClass + ".java");
            }
        }
        if (template.generatesProtocol()) {
            result.add(new FileMapping(template.protocolTemplate(), template.protocolOutput()));
            result.add(new FileMapping("protoId.txt.tpl", "src/main/protocol/protoId.txt"));
        }
        return List.copyOf(result);
    }

    private void addIfPresent(final List<FileMapping> mappings, final Path sourceRoot,
            final String template, final String output) {
        if (Files.isRegularFile(sourceRoot.resolve(template))) {
            mappings.add(new FileMapping(template, output));
        }
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
        write(targetRoot, mapping.output(), content);
    }

    private void write(final Path targetRoot, final String relativePath, final String content) throws IOException {
        Path output = targetRoot.resolve(relativePath).normalize();
        if (!output.startsWith(targetRoot)) {
            throw new IllegalStateException("scaffold output escapes its root");
        }
        Files.createDirectories(Objects.requireNonNull(output.getParent(), "output.parent"));
        Files.writeString(output, content, StandardCharsets.UTF_8);
    }

    private void writeOwnershipManifest(
            final Path target,
            final List<FileMapping> mappings,
            final String assemblyOutput,
            final String configOutput,
            final ProjectScaffoldRequest request,
            final Map<String, String> values) throws IOException {
        List<OwnedFile> files = new ArrayList<>();
        for (FileMapping mapping : mappings) {
            if (!mapping.output().equals("zero-scaffold.json")) {
                files.add(new OwnedFile(mapping.output(), "generated", mapping.template(), sha256(target.resolve(mapping.output()))));
            }
        }
        files.add(new OwnedFile(assemblyOutput, "generated", "<runtime-assembly>", sha256(target.resolve(assemblyOutput))));
        files.add(new OwnedFile(configOutput, "generated", "<configuration>", sha256(target.resolve(configOutput))));
        files.add(new OwnedFile("zero-scaffold.json", "generated", "zero-scaffold.json.tpl", ""));
        files.sort(java.util.Comparator.comparing(OwnedFile::path));
        StringBuilder rendered = new StringBuilder();
        for (OwnedFile file : files) {
            if (rendered.length() > 0) rendered.append(",\n");
            rendered.append("    {\"path\": ").append(jsonString(file.path()))
                    .append(", \"owner\": ").append(jsonString(file.owner()))
                    .append(", \"template\": ").append(jsonString(file.template()))
                    .append(", \"sha256\": ").append(jsonString(file.sha256())).append("}");
        }
        values.put("__OWNERSHIP_FILES__", rendered.toString());
        String manifest = Files.readString(
                request.templateRoot().resolve(request.template().directory()).resolve("zero-scaffold.json.tpl"),
                StandardCharsets.UTF_8);
        for (Map.Entry<String, String> entry : values.entrySet()) manifest = manifest.replace(entry.getKey(), entry.getValue());
        write(target, "zero-scaffold.json", manifest);
    }

    private String sha256(final Path file) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record OwnedFile(String path, String owner, String template, String sha256) {
    }

    private boolean hasAnyChild(final Path path) throws IOException {
        try (var stream = Files.list(path)) {
            return stream.findAny().isPresent();
        }
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
