package group.zn.zero.codegen.scaffold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import group.zn.zero.runtime.api.BindingCardinality;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.capability.MavenCoordinate;
import group.zn.zero.runtime.capability.RuntimeCapability;
import group.zn.zero.runtime.capability.RuntimeCapabilityModel;
import group.zn.zero.runtime.capability.RuntimeProviderCapability;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import java.io.StringReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证扩展元数据真正进入生成物，且文本、依赖与运行行为保持一致。 */
class ScaffoldExtensionTest {

    /** 隔离生成目录。 */
    @TempDir
    private Path temporary;

    /** 第三方组件同时驱动依赖、安装表达式、配置和 manifest，并可编译配置默认值。 */
    @Test
    void customDefinitionShouldReachEveryGeneratedArtifact() throws Exception {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("custom.path", " C:\\test\\\"quoted\"\nnext\t=#! ");
        for (int index = 0; index < 12; index++) {
            defaults.put("custom.option." + index, Integer.toString(index));
        }
        var definitions = new ArrayList<>(ScaffoldComponents.standardDefinitions());
        ComponentId provider = ComponentId.of("application.custom");
        var builder = RuntimeCapabilityModel.builder("extension.test");
        var standard = StandardRuntimeCapabilityModel.instance();
        standard.capabilities().forEach(builder::capability);
        standard.providers().forEach(builder::provider);
        builder.capability(new RuntimeCapability("custom.service", BindingCardinality.SINGLE, List.of(),
                List.of("local"), List.of(MavenCoordinate.zero("custom-api"))));
        builder.provider(new RuntimeProviderCapability(provider, List.of("custom.service"), List.of(),
                List.of("local"), List.of(MavenCoordinate.zero("custom-provider"))));
        definitions.add(new ScaffoldComponentDefinition("custom", List.of(provider),
                List.of(MavenCoordinate.zero("custom-extra")), "application.CustomRuntime.module()", defaults,
                "custom.endpoint=https://example.test/a%20b\ncustom.name=${projectName}"));
        var components = new ScaffoldComponents(builder.build(), definitions);
        Path output = temporary.resolve("generated");

        new ProjectScaffoldGenerator(components).generate(request(output, List.of("custom")));

        String pom = Files.readString(output.resolve("pom.xml"));
        assertTrue(pom.contains("<artifactId>custom-provider</artifactId>"));
        assertTrue(pom.contains("<artifactId>custom-extra</artifactId>"));
        String assembly = Files.readString(output.resolve("src/main/java/group/zn/sample/RuntimeAssembly.java"));
        assertTrue(assembly.contains("assembly.install(application.CustomRuntime.module());"));
        var manifest = JsonParser.parseString(Files.readString(output.resolve("zero-scaffold.json"))).getAsJsonObject();
        assertTrue(manifest.getAsJsonArray("selectedProviders").toString().contains("application.custom"));
        assertTrue(manifest.getAsJsonArray("runtimeCapabilities").toString().contains("custom.service"));
        Properties properties = new Properties();
        properties.load(new StringReader(Files.readString(output.resolve("config/application.properties.example"))));
        defaults.forEach((key, value) -> assertEquals(value, properties.getProperty(key)));
        assertEquals("https://example.test/a%20b", properties.getProperty("custom.endpoint"));
        assertEquals("extension", properties.getProperty("custom.name"));
        Map<?, ?> compiled = compileDefaults(ScaffoldConfiguration.defaults(components.resolve(List.of(), List.of("custom"))));
        defaults.forEach((key, value) -> assertEquals(value, compiled.get(key)));
        assertEquals("local", compiled.get("zero.mode"));
    }

    /** 未知组件与冲突默认值在创建输出目录之前失败。 */
    @Test
    void invalidSelectionsShouldNotWriteFiles() {
        var definitions = new ArrayList<>(ScaffoldComponents.standardDefinitions());
        definitions.add(new ScaffoldComponentDefinition("bad", List.of(), List.of(), "",
                Map.of("zero.mode", "production"), ""));
        var generator = new ProjectScaffoldGenerator(new ScaffoldComponents(
                StandardRuntimeCapabilityModel.instance(), definitions));
        Path output = temporary.resolve("must-not-exist");
        assertThrows(IllegalArgumentException.class, () -> generator.generate(request(output, List.of("bad"))));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(request(output, List.of("unknown"))));
        assertFalse(Files.exists(output));
    }

    /** 内置 discovery 仍生成实际安装调用，不能只声明 manifest 能力。 */
    @Test
    void discoveryShouldInstallItsRuntimeModule() throws Exception {
        Path output = temporary.resolve("discovery");
        new ProjectScaffoldGenerator(StandardRuntimeCapabilityModel.instance())
                .generate(request(output, List.of("discovery")));
        assertTrue(Files.readString(output.resolve("src/main/java/group/zn/sample/RuntimeAssembly.java"))
                .contains("assembly.install(group.zn.zero.runtime.discovery.DiscoveryRuntime.module());"));
    }

    /** 返回使用真实 runtime 模板的请求。 */
    private ProjectScaffoldRequest request(final Path output, final List<String> components) {
        return new ProjectScaffoldRequest("extension", "group.zn.sample", output, "0.1.1",
                ScaffoldCatalog.standard().require("runtime"), Path.of("../templates"), "", components, false);
    }

    /** 用 javac 与类加载执行生成的默认值，验证转义和大于十项的 Map 构造。 */
    private Map<?, ?> compileDefaults(final String defaults) throws Exception {
        Path source = temporary.resolve("GeneratedDefaults.java");
        Files.writeString(source, "import java.util.Map; public class GeneratedDefaults {"
                + "public static Map<String, String> values() { return Map.ofEntries(" + defaults + "); }}");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, source.toString()));
        try (var loader = new URLClassLoader(new URL[] {temporary.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            return (Map<?, ?>) loader.loadClass("GeneratedDefaults").getMethod("values").invoke(null);
        }
    }
}
