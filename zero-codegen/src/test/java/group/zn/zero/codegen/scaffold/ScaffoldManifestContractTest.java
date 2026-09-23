package group.zn.zero.codegen.scaffold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 脚手架 manifest 与模板契约测试。 */
class ScaffoldManifestContractTest {
    @TempDir
    private Path temporary;

    @Test
    void everyPublicTemplateRendersOwnManifestAndGeneratedFiles() throws Exception {
        ScaffoldCatalog catalog = ScaffoldCatalog.standard();
        for (var template : catalog.templates()) {
            String templateId = template.id();
            Path output = temporary.resolve("template-" + templateId);
            new ProjectScaffoldGenerator(catalog.capabilityModel()).generate(new ProjectScaffoldRequest(
                    "sample-game", "group.example.game", output, "0.1.0-SNAPSHOT", template,
                    Path.of("../templates"), "", List.of(), false));
            assertTrue(Files.isRegularFile(output.resolve("zero-scaffold.json")), templateId);
            JsonArray files = JsonParser.parseString(Files.readString(output.resolve("zero-scaffold.json")))
                    .getAsJsonObject().getAsJsonArray("files");
            assertTrue(files.size() >= 10, templateId);
            files.forEach(value -> {
                String path = value.getAsJsonObject().get("path").getAsString();
                assertTrue(Files.isRegularFile(output.resolve(path)), templateId + ":" + path);
            });
        }
    }

    @Test
    void everyPublicComponentMustHaveConsistentManifestAndDirectDependencies() throws Exception {
        for (String component : ScaffoldComponents.supported()) {
            check(List.of(component), component);
        }
        check(List.of("data", "redis", "cache", "custom-actor", "discovery"), "mixed");
        check(ScaffoldComponents.supported().stream()
                .filter(id -> !Set.of("redis", "kafka", "nacos", "mongo", "postgresql").contains(id)).toList(), "all-local");
        check(List.of("rpc", "discovery", "kafka", "nacos", "mongo", "redis", "postgresql"), "distributed");
    }

    private void check(final List<String> requested, final String name) throws Exception {
        var catalog = ScaffoldCatalog.standard();
        var template = catalog.require("runtime");
        var selection = new ScaffoldComponents(catalog.capabilityModel()).resolve(template.capabilityIds(), requested);
        Path output = temporary.resolve(name);
        new ProjectScaffoldGenerator(catalog.capabilityModel()).generate(new ProjectScaffoldRequest(
                "sample-game", "group.example.game", output, "0.1.0-SNAPSHOT", template,
                Path.of("../templates"), "", requested, false));
        var manifest = JsonParser.parseString(Files.readString(output.resolve("zero-scaffold.json"))).getAsJsonObject();
        assertEquals(Set.copyOf(selection.components()), strings(manifest.getAsJsonArray("selectedComponents")));
        assertEquals(Set.copyOf(selection.providers()), strings(manifest.getAsJsonArray("selectedProviders")));
        assertEquals(Set.copyOf(selection.capabilities()), strings(manifest.getAsJsonArray("runtimeCapabilities")));
        assertEquals(selection.external(), manifest.get("requiresExternalServices").getAsBoolean());
        var document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(output.resolve("pom.xml").toFile());
        var xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
        var dependencies = (org.w3c.dom.NodeList) xpath.evaluate(
                "/project/dependencies/dependency[groupId='group.zn.zero']/artifactId", document,
                javax.xml.xpath.XPathConstants.NODESET);
        Set<String> artifacts = new HashSet<>();
        for (int index = 0; index < dependencies.getLength(); index++) {
            artifacts.add(dependencies.item(index).getTextContent());
        }
        assertEquals(artifacts, strings(manifest.getAsJsonArray("frameworkComponents")));
        JsonArray files = manifest.getAsJsonArray("files");
        assertTrue(files != null && files.size() >= 10);
        files.forEach(value -> {
            try {
                var file = value.getAsJsonObject();
                String path = file.get("path").getAsString();
                assertTrue(Files.isRegularFile(output.resolve(path)));
                assertEquals("generated", file.get("owner").getAsString());
                assertTrue(file.get("template").getAsString().length() > 0);
                String hash = file.get("sha256").getAsString();
                if (!path.equals("zero-scaffold.json")) {
                    assertEquals(hash, sha256(output.resolve(path)));
                }
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
    }

    private String sha256(final Path file) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        StringBuilder result = new StringBuilder();
        for (byte value : digest) result.append(String.format("%02x", value));
        return result.toString();
    }

    private Set<String> strings(final JsonArray array) {
        Set<String> values = new HashSet<>();
        array.forEach(value -> values.add(value.getAsString()));
        assertEquals(array.size(), values.size(), "manifest arrays must not contain duplicates");
        return values;
    }
}
