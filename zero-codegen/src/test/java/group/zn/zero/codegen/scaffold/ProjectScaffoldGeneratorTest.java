package group.zn.zero.codegen.scaffold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 项目模板选择与渲染 focused tests。 */
class ProjectScaffoldGeneratorTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void catalogShouldSelectTemplatesFromOneCapabilityModel() {
        ScaffoldCatalog catalog = ScaffoldCatalog.standard();
        ScaffoldSelector selector = new ScaffoldSelector(catalog);

        assertEquals(8, catalog.templates().size());
        assertEquals("world-shard", selector.select("open world shard migration").id());
        assertEquals("scene-sync", selector.select("aoi scene visibility").id());
        assertTrue(catalog.templates().stream().allMatch(template ->
                template.capabilityIds().stream().allMatch(id -> catalog.capabilityModel().capability(id).isPresent())));
    }

    @Test
    void generatorShouldRenderCapabilitiesDependenciesAndKnownFiles() throws IOException {
        ScaffoldCatalog catalog = ScaffoldCatalog.standard();
        ScaffoldTemplate template = catalog.require("local");
        Path templateRoot = temporaryDirectory.resolve("templates");
        createMinimalTemplate(templateRoot.resolve(template.directory()), template);
        Path output = temporaryDirectory.resolve("generated");
        ProjectScaffoldRequest request = new ProjectScaffoldRequest(
                "sample-game",
                "group.zn.sample.game",
                output,
                "0.1.0-SNAPSHOT",
                template,
                templateRoot,
                "local rpg",
                List.of(),
                false);

        ProjectScaffoldGenerator.ProjectScaffoldResult result =
                new ProjectScaffoldGenerator(catalog.capabilityModel()).generate(request);

        assertEquals(12, result.fileCount());
        String pom = Files.readString(output.resolve("pom.xml"), StandardCharsets.UTF_8);
        String manifest = Files.readString(output.resolve("zero-scaffold.json"), StandardCharsets.UTF_8);
        assertFalse(pom.contains("<artifactId>zero-server-starter</artifactId>"));
        assertFalse(pom.contains("<artifactId>zero-codegen</artifactId>"));
        assertTrue(pom.contains("<artifactId>zero-runtime-bootstrap</artifactId>"));
        assertTrue(pom.contains("<artifactId>zero-player</artifactId>"));
        assertTrue(manifest.contains("zero.actor.scheduler"));
        assertTrue(manifest.contains("zero-runtime"));
        assertFalse(pom.contains("__"));
        assertFalse(manifest.contains("__"));
        JsonArray files = JsonParser.parseString(manifest).getAsJsonObject().getAsJsonArray("files");
        assertEquals(12, files.size());
        assertEquals(files.size(), files.size());
        Set<String> paths = new java.util.HashSet<>();
        files.forEach(value -> paths.add(value.getAsJsonObject().get("path").getAsString()));
        assertEquals(files.size(), paths.size());
        assertTrue(files.get(0).getAsJsonObject().get("path").getAsString()
                .compareTo(files.get(files.size() - 1).getAsJsonObject().get("path").getAsString()) <= 0);
        files.forEach(value -> {
            JsonObject file = value.getAsJsonObject();
            String sha256 = file.get("sha256").getAsString();
            boolean selfManaged = file.get("path").getAsString().equals("zero-scaffold.json");
            assertEquals("generated", file.get("owner").getAsString());
            assertFalse(file.get("template").getAsString().isBlank());
            assertTrue(selfManaged ? sha256.isEmpty() : sha256.matches("[0-9a-f]{64}"));
        });
        boolean hasManifest = false;
        boolean hasAssembly = false;
        boolean hasConfig = false;
        for (var value : files) {
            String path = value.getAsJsonObject().get("path").getAsString();
            hasManifest |= path.equals("zero-scaffold.json");
            hasAssembly |= path.endsWith("RuntimeAssembly.java");
            hasConfig |= path.equals("config/application.properties.example");
        }
        assertTrue(hasManifest && hasAssembly && hasConfig);
        assertThrows(IllegalStateException.class, () ->
                new ProjectScaffoldGenerator(catalog.capabilityModel()).generate(request));
    }

    @Test
    void networkSelectionIncludesListenerRuntimeAndDefaultDoesNot() throws Exception {
        var catalog = ScaffoldCatalog.standard();
        Path output = temporaryDirectory.resolve("network-game");
        new ProjectScaffoldGenerator(catalog.capabilityModel()).generate(new ProjectScaffoldRequest(
                "network-game", "group.zn.network.game", output, "0.1.0-SNAPSHOT", catalog.require("runtime"),
                Path.of("../templates"), "", List.of("net"), false));
        String pom = Files.readString(output.resolve("pom.xml"));
        String config = Files.readString(output.resolve("config/application.properties.example"));
        assertTrue(pom.contains("<artifactId>zero-net</artifactId>"));
        assertTrue(pom.contains("<artifactId>zero-runtime-net</artifactId>"));
        assertTrue(config.contains("zero.net.host=127.0.0.1"));
        assertTrue(config.contains("zero.net.port=0"));

        Path local = temporaryDirectory.resolve("local-no-network");
        new ProjectScaffoldGenerator(catalog.capabilityModel()).generate(new ProjectScaffoldRequest(
                "local-game", "group.zn.local.game", local, "0.1.0-SNAPSHOT", catalog.require("runtime"),
                Path.of("../templates"), "", List.of(), false));
        String localPom = Files.readString(local.resolve("pom.xml"));
        assertFalse(localPom.contains("<artifactId>zero-net</artifactId>"));
        assertFalse(localPom.contains("<artifactId>zero-runtime-net</artifactId>"));
    }

    @Test
    void externalGameplaySelectionKeepsBusinessTemplateAndExplicitAdapterConfiguration() throws IOException {
        var catalog = ScaffoldCatalog.standard();
        Path output = temporaryDirectory.resolve("external-game");
        var request = new ProjectScaffoldRequest("sample-game", "group.zn.sample.game", output,
                "0.1.0-SNAPSHOT", catalog.require("local"), Path.of("../templates"), "", List.of("redis"), false);

        new ProjectScaffoldGenerator(catalog.capabilityModel()).generate(request);
        String assembly = Files.readString(output.resolve("src/main/java/group/zn/sample/game/RuntimeAssembly.java"));
        String config = Files.readString(output.resolve("config/application.properties.example"));
        assertTrue(assembly.contains("RedisRuntime.module()"));
        assertTrue(assembly.contains("ZeroRuntimeExecutors.localPrototype"));
        assertTrue(config.contains("zero.mode=external-test"));
        assertTrue(config.contains("zero.adapter.data.redis.enabled=true"));
    }

    @Test
    void realTemplatesSeparateBuildToolsAndGenerateOnlySelectedDependencies() throws Exception {
        ScaffoldCatalog catalog = ScaffoldCatalog.standard();
        Path root = Path.of("../templates").toAbsolutePath().normalize();
        for (String id : List.of("runtime", "local", "room")) {
            Path output = temporaryDirectory.resolve(id);
            new ProjectScaffoldGenerator(catalog.capabilityModel()).generate(new ProjectScaffoldRequest(
                    "sample-game", "group.zn.sample.game", output, "0.1.0-SNAPSHOT", catalog.require(id),
                    root, "", List.of(), false));
            var document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(output.resolve("pom.xml").toFile());
            var xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
            assertEquals("0", xpath.evaluate("count(/project/dependencies/dependency[artifactId='zero-codegen'])", document));
            assertEquals("0", xpath.evaluate("count(/project/dependencies/dependency[artifactId='zero-server-starter'])", document));
            String buildTools = xpath.evaluate("count(/project/build/plugins/plugin/dependencies/dependency[artifactId='zero-codegen'])", document);
            assertEquals(id.equals("runtime") ? "0" : "1", buildTools);
            String player = xpath.evaluate("count(/project/dependencies/dependency[artifactId='zero-player'])", document);
            assertEquals(id.equals("local") ? "1" : "0", player);
            assertEquals(!id.equals("runtime"), Files.exists(output.resolve("src/main/protocol")));
        }
    }

    private void createMinimalTemplate(final Path root, final ScaffoldTemplate template) throws IOException {
        Files.createDirectories(root);
        write(root, "pom.xml.tpl", "<dependencies>\n__FRAMEWORK_DEPENDENCIES_XML__\n</dependencies>\n");
        write(root, template.protocolTemplate(), "protocol __PROJECT_NAME__\n");
        write(root, "protoId.txt.tpl", "1=__PACKAGE__\n");
        write(root, "Application.java.tpl", "package __PACKAGE__; class __APP_CLASS__ {}\n");
        write(root, "ApplicationTest.java.tpl", "package __PACKAGE__; class __TEST_CLASS__ {}\n");
        write(root, "README.md.tpl", "__TEMPLATE_NAME__ __SUMMARY_PREFIX__\n");
        write(root, "BUSINESS_GUIDE.md.tpl", "__TEMPLATE_USE_CASE__\n");
        write(root, "COMPONENTS.md.tpl", "__TEMPLATE_DESCRIPTION__\n");
        write(root, "NEXT_STEPS.md.tpl", "__PRODUCTION_GAP__\n");
        write(root, "zero-scaffold.json.tpl", "{\n"
                + "  \"schemaVersion\": 1,\n"
                + "  \"ownershipSchemaVersion\": 1,\n"
                + "  \"files\": [\n__OWNERSHIP_FILES__\n  ],\n"
                + "  \"project\": __PROJECT_NAME_JSON__,\n"
                + "  \"components\": [\n__FRAMEWORK_COMPONENTS_JSON__\n  ],\n"
                + "  \"capabilities\": [\n__RUNTIME_CAPABILITIES_JSON__\n  ]\n}\n");
    }

    private void write(final Path root, final String file, final String content) throws IOException {
        Path target = root.resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
