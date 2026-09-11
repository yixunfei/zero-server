package group.zn.zero.codegen.scaffold;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScaffoldManifestContractTest {
    @TempDir
    private Path temporary;

    @Test
    void everyPublicComponentMustHaveConsistentManifestAndDirectDependencies() throws Exception {
        for (String component : ScaffoldComponents.supported()) {
            check(List.of(component), component);
        }
        check(List.of("data", "redis", "cache", "custom-actor", "discovery"), "mixed");
        check(ScaffoldComponents.supported().stream().filter(id -> !id.equals("redis")).toList(), "all-local");
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
    }

    private Set<String> strings(final JsonArray array) {
        Set<String> values = new HashSet<>();
        array.forEach(value -> values.add(value.getAsString()));
        assertEquals(array.size(), values.size(), "manifest arrays must not contain duplicates");
        return values;
    }
}
