package group.zn.zero.codegen.scaffold;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import group.zn.zero.runtime.api.BindingCardinality;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.capability.MavenCoordinate;
import group.zn.zero.runtime.capability.RuntimeCapability;
import group.zn.zero.runtime.capability.RuntimeCapabilityModel;
import group.zn.zero.runtime.capability.RuntimeProviderCapability;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 显式读取可信 JSON 扩展目录；不加载类或执行安装表达式。@author zn */
public final class ScaffoldCatalogFile {
    /** 支持的文件格式版本；版本与框架发布版本无关。 */
    public static final int SCHEMA_VERSION = 1;

    private ScaffoldCatalogFile() { }

    /**
     * 在标准目录上追加显式声明，全部校验成功后返回；不写文件、不扫描 classpath。
     * @param path UTF-8 JSON 文件，必须是应用信任的代码生成输入。
     * @return 已冻结目录，可跨线程使用。
     * @throws IOException 文件无法读取时抛出。
     * @throws IllegalArgumentException 格式、版本、引用或重复声明错误时抛出，不回显内容。
     */
    public static ScaffoldComponents read(final Path path) throws IOException {
        if (Files.size(path) > 1_048_576) {
            throw new IllegalArgumentException("scaffold catalog exceeds 1 MiB");
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            fields(root, Set.of("schemaVersion", "id", "capabilities", "providers", "components", "bindings"));
            if (!Integer.toString(SCHEMA_VERSION).equals(root.get("schemaVersion").getAsString())) {
                throw new IllegalArgumentException("unsupported scaffold catalog schemaVersion");
            }
            String id = text(root, "id");
            RuntimeCapabilityModel.Builder model = RuntimeCapabilityModel.builder(id);
            StandardRuntimeCapabilityModel.instance().capabilities().forEach(model::capability);
            StandardRuntimeCapabilityModel.instance().providers().forEach(model::provider);
            array(root, "capabilities").forEach(value -> model.capability(capability(value.getAsJsonObject())));
            array(root, "providers").forEach(value -> model.provider(provider(value.getAsJsonObject())));
            List<ScaffoldComponentDefinition> definitions = new ArrayList<>(ScaffoldComponents.standardDefinitions());
            array(root, "components").forEach(value -> definitions.add(component(value.getAsJsonObject())));
            array(root, "bindings").forEach(value -> definitions.add(binding(value.getAsJsonObject(), model)));
            return new ScaffoldComponents(id, model.build(), definitions);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("invalid scaffold catalog: check schema version, fields, IDs and references");
        }
    }

    /** 单份绑定声明同时派生能力图与 Java 工厂表达式，不加载应用类。 */
    private static ScaffoldComponentDefinition binding(final JsonObject value, final RuntimeCapabilityModel.Builder model) {
        fields(value, Set.of("id", "provider", "key", "type", "factory", "requires", "profiles", "artifacts"));
        String id = text(value, "id");
        ComponentId provider = ComponentId.of(text(value, "provider"));
        String key = text(value, "key");
        if (!key.matches("[A-Za-z][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException("invalid binding key");
        }
        List<String> profiles = value.has("profiles") ? strings(value, "profiles")
                : List.of("local", "standalone", "external-test", "production");
        List<String> requires = new ArrayList<>();
        List<String> requiredKeys = new ArrayList<>();
        for (JsonElement entry : array(value, "requires")) {
            JsonObject dependency = entry.getAsJsonObject();
            fields(dependency, Set.of("key", "type"));
            requires.add(text(dependency, "key"));
            requiredKeys.add(bindingKey(dependency));
        }
        List<MavenCoordinate> coordinates = value.has("artifacts") ? artifacts(value)
                : List.of(MavenCoordinate.zero("zero-runtime"));
        model.capability(new RuntimeCapability(key, BindingCardinality.SINGLE, List.of(), profiles, coordinates));
        model.provider(new RuntimeProviderCapability(provider, List.of(key), requires, profiles, coordinates));
        String expression = "group.zn.zero.runtime.assembly.RuntimeModule.component("
                + "group.zn.zero.runtime.api.ComponentId.of(" + javaString(provider.value()) + "), " + bindingKey(value) + ")"
                + (requiredKeys.isEmpty() ? "" : ".require(" + String.join(", ", requiredKeys) + ")")
                + ".create(context -> " + text(value, "factory") + ")";
        return ScaffoldComponentDefinition.runtime(id, expression, provider);
    }

    /** 只允许合法 Java 类型名称，能力标识按字符串字面量转义。 */
    private static String bindingKey(final JsonObject value) {
        String type = text(value, "type");
        if (!javax.lang.model.SourceVersion.isName(type)) throw new IllegalArgumentException("invalid binding type");
        return "group.zn.zero.runtime.api.ComponentKey.single(" + javaString(text(value, "key")) + ", " + type + ".class)";
    }

    private static String javaString(final String text) {
        return new com.google.gson.Gson().toJson(text);
    }

    private static RuntimeCapability capability(final JsonObject value) {
        fields(value, Set.of("id", "cardinality", "requires", "profiles", "artifacts"));
        return new RuntimeCapability(text(value, "id"), BindingCardinality.valueOf(text(value, "cardinality")),
                strings(value, "requires"), strings(value, "profiles"), artifacts(value));
    }

    private static RuntimeProviderCapability provider(final JsonObject value) {
        fields(value, Set.of("id", "provides", "requires", "profiles", "artifacts"));
        return new RuntimeProviderCapability(ComponentId.of(text(value, "id")), strings(value, "provides"),
                strings(value, "requires"), strings(value, "profiles"), artifacts(value));
    }

    private static ScaffoldComponentDefinition component(final JsonObject value) {
        fields(value, Set.of("id", "providers", "artifacts", "installExpression", "configDefaults", "configExample"));
        Map<String, String> defaults = new LinkedHashMap<>();
        if (value.has("configDefaults")) {
            value.getAsJsonObject("configDefaults").entrySet().forEach(entry ->
                    defaults.put(entry.getKey(), string(entry.getValue())));
        }
        return new ScaffoldComponentDefinition(text(value, "id"), strings(value, "providers").stream()
                .map(ComponentId::of).toList(), artifacts(value), text(value, "installExpression"), defaults,
                value.has("configExample") ? text(value, "configExample") : "");
    }

    private static List<MavenCoordinate> artifacts(final JsonObject value) {
        List<MavenCoordinate> result = new ArrayList<>();
        for (JsonElement element : array(value, "artifacts")) {
            JsonObject coordinate = element.getAsJsonObject();
            fields(coordinate, Set.of("groupId", "artifactId"));
            result.add(new MavenCoordinate(text(coordinate, "groupId"), text(coordinate, "artifactId")));
        }
        return List.copyOf(result);
    }

    private static List<String> strings(final JsonObject value, final String field) {
        List<String> result = new ArrayList<>();
        array(value, field).forEach(element -> result.add(string(element)));
        return List.copyOf(result);
    }

    private static JsonArray array(final JsonObject value, final String field) {
        return value.has(field) ? value.getAsJsonArray(field) : new JsonArray();
    }

    private static String text(final JsonObject value, final String field) {
        return string(value.get(field));
    }

    private static String string(final JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("catalog field must be a string");
        }
        return value.getAsString();
    }

    private static void fields(final JsonObject value, final Set<String> allowed) {
        if (!allowed.containsAll(value.keySet())) {
            throw new IllegalArgumentException("unknown scaffold catalog field");
        }
    }
}
