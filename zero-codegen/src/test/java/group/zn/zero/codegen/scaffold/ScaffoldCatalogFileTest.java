package group.zn.zero.codegen.scaffold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 显式目录的 CLI 生成、升级一致性和写盘前拒绝契约。@author zn */
class ScaffoldCatalogFileTest {
    /** 测试输出。 */
    @TempDir
    private Path directory;

    /** 用户无需重编译生成器即可选择扩展组件；升级必须复用同一目录。 */
    @Test
    void cliAndUpgradeUseTheSameExplicitCatalog() throws Exception {
        Path catalog = Path.of("../examples/scaffold-catalog/greeting.catalog.json");
        Path output = directory.resolve("greeting");
        ProjectScaffoldCli.main(new String[] {"--template", "runtime", "--projectName", "greeting",
                "--packageName", "group.zn.sample", "--outputDir", output.toString(),
                "--templateRoot", "../templates", "--catalog", catalog.toString(), "--components", "greeting"});
        var manifest = JsonParser.parseString(Files.readString(output.resolve("zero-scaffold.json"))).getAsJsonObject();
        assertEquals("example.greeting.v1", manifest.get("catalogId").getAsString());
        assertEquals(1, manifest.get("catalogSchemaVersion").getAsInt());
        assertTrue(manifest.get("selectedProviders").toString().contains("application.greeting"));
        for (String wrapper : ScaffoldWrapper.FILES) {
            assertTrue(Files.isRegularFile(output.resolve(wrapper)));
            assertTrue(manifest.getAsJsonArray("files").toString().contains(wrapper));
        }
        String assembly = Files.readString(output.resolve("src/main/java/group/zn/sample/RuntimeAssembly.java"));
        assertTrue(assembly.contains("RuntimeModule.component("));
        assertTrue(assembly.contains(".create(context -> \"hello\")"));
        assertTrue(Files.readString(output.resolve("pom.xml"))
                .contains("<zero.version>0.1.2-SNAPSHOT</zero.version>"));
        var request = new ProjectScaffoldRequest("greeting", "group.zn.sample", output, "0.1.2-SNAPSHOT",
                ScaffoldCatalog.standard().require("runtime"), Path.of("../templates"), "", List.of("greeting"), false);
        var plan = new ScaffoldUpgradeService(ScaffoldCatalogFile.read(catalog)).plan(request);
        assertFalse(plan.hasConflicts());
        assertFalse(plan.requiresMigration());
        assertEquals("changes=0|conflicts=0", plan.summary());
        assertThrows(ScaffoldUpgradeService.ScaffoldUpgradeException.class,
                () -> new ScaffoldUpgradeService().plan(request));
    }

    /** 未知字段、版本、重复组件和未知 Provider 不能创建目标目录。 */
    @Test
    void invalidCatalogsFailBeforeWriting() throws Exception {
        for (String body : List.of(
                "{\"schemaVersion\":2,\"id\":\"test\"}",
                "{\"schemaVersion\":1,\"id\":\"test\",\"componets\":[]}",
                "{\"schemaVersion\":1,\"id\":\"test\",\"components\":[{\"id\":\"bootstrap\",\"installExpression\":\"\"}]}",
                "{\"schemaVersion\":1,\"id\":\"test\",\"components\":[{\"id\":\"bad\",\"providers\":[\"missing\"],\"installExpression\":\"bad()\"}]}")) {
            Path catalog = directory.resolve("invalid.json");
            Files.writeString(catalog, body);
            Path output = directory.resolve("absent");
            assertThrows(IllegalArgumentException.class, () -> ProjectScaffoldCli.main(new String[] {
                    "--catalog", catalog.toString(), "--outputDir", output.toString()}));
            assertFalse(Files.exists(output));
        }
    }

    /** 两个 Provider 争用同一单值能力必须在解析选择时失败。 */
    @Test
    void conflictingProvidersAndUnsupportedProfilesAreRejected() throws Exception {
        String source = Files.readString(Path.of("../examples/scaffold-catalog/greeting.catalog.json"));
        var root = JsonParser.parseString(source).getAsJsonObject();
        var second = root.getAsJsonArray("bindings").get(0).deepCopy().getAsJsonObject();
        second.addProperty("id", "second");
        second.addProperty("provider", "application.second");
        root.getAsJsonArray("bindings").add(second);
        Path catalog = directory.resolve("conflict.json");
        Files.writeString(catalog, root.toString());
        assertThrows(IllegalArgumentException.class, () -> ScaffoldCatalogFile.read(catalog));
        root.getAsJsonArray("bindings").remove(1);
        root.getAsJsonArray("bindings").get(0).getAsJsonObject()
                .add("profiles", JsonParser.parseString("[\"production\"]"));
        Files.writeString(catalog, root.toString());
        var unsupported = ScaffoldCatalogFile.read(catalog);
        assertThrows(IllegalArgumentException.class, () -> unsupported.resolve(List.of(), List.of("greeting")));
    }

    /** 扩展声明的依赖闭包必须进入组件和 Maven 坐标选择。 */
    @Test
    void extensionDependenciesAreResolvedBeforeGeneration() throws Exception {
        var root = JsonParser.parseString(Files.readString(Path.of("../examples/scaffold-catalog/greeting.catalog.json")))
                .getAsJsonObject();
        root.getAsJsonArray("bindings").get(0).getAsJsonObject()
                .add("requires", JsonParser.parseString("""
                        [{"key":"zero.actor.scheduler","type":"group.zn.zero.actor.scheduler.ActorScheduler"}]
                        """));
        Path catalog = directory.resolve("dependencies.json");
        Files.writeString(catalog, root.toString());
        var selection = ScaffoldCatalogFile.read(catalog).resolve(List.of(), List.of("greeting"));
        assertTrue(selection.components().contains("actor"));
        assertTrue(selection.artifacts().stream().anyMatch(coordinate -> coordinate.artifactId().equals("zero-runtime-actor")));
    }
}
