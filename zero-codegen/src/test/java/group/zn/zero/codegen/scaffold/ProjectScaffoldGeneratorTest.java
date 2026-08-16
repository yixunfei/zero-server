package group.zn.zero.codegen.scaffold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 项目模板选择与渲染 focused tests。 */
class ProjectScaffoldGeneratorTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void catalogShouldSelectAllSevenTemplatesFromOneCapabilityModel() {
        ScaffoldCatalog catalog = ScaffoldCatalog.standard();
        ScaffoldSelector selector = new ScaffoldSelector(catalog);

        assertEquals(7, catalog.templates().size());
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
                false);

        ProjectScaffoldGenerator.ProjectScaffoldResult result =
                new ProjectScaffoldGenerator(catalog.capabilityModel()).generate(request);

        assertEquals(10, result.fileCount());
        String pom = Files.readString(output.resolve("pom.xml"), StandardCharsets.UTF_8);
        String manifest = Files.readString(output.resolve("zero-scaffold.json"), StandardCharsets.UTF_8);
        assertTrue(pom.contains("<artifactId>zero-server-starter</artifactId>"));
        assertTrue(pom.contains("<artifactId>zero-codegen</artifactId>"));
        assertTrue(manifest.contains("zero.actor.scheduler"));
        assertTrue(manifest.contains("zero-runtime"));
        assertFalse(pom.contains("__"));
        assertFalse(manifest.contains("__"));
        assertThrows(IllegalStateException.class, () ->
                new ProjectScaffoldGenerator(catalog.capabilityModel()).generate(request));
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
