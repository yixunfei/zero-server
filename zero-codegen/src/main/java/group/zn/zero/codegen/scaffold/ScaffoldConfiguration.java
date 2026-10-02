package group.zn.zero.codegen.scaffold;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** 按组件元数据生成启用配置和外部配置样例。 */
final class ScaffoldConfiguration {

    private ScaffoldConfiguration() {
    }

    static String defaults(final ScaffoldComponents.Selection selection) {
        return switches(selection).entrySet().stream()
                .map(entry -> "Map.entry(" + javaString(entry.getKey()) + ", " + javaString(entry.getValue()) + ")")
                .collect(Collectors.joining(",\n                "));
    }

    static String example(final ProjectScaffoldRequest request, final ScaffoldComponents.Selection selection) {
        StringBuilder result = new StringBuilder("zero.name=" + request.projectName() + "\n");
        switches(selection).forEach((key, value) -> result.append(propertyText(key)).append('=')
                .append(propertyText(value)).append('\n'));
        for (String component : selection.components()) {
            String template = selection.definition(component).configExample();
            if (!template.isEmpty()) {
                result.append(template.replace("${projectName}", request.projectName()));
                if (!template.endsWith("\n")) {
                    result.append('\n');
                }
            }
        }
        return result.toString();
    }

    private static Map<String, String> switches(final ScaffoldComponents.Selection selection) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("zero.mode", selection.external() ? "external-test" : "local");
        for (String component : selection.components()) {
            new TreeMap<>(selection.definition(component).configDefaults()).forEach((key, value) -> {
                String previous = values.putIfAbsent(key, value);
                if (key.equals("zero.name") || previous != null && !previous.equals(value)) {
                    throw new IllegalArgumentException("conflicting scaffold config default: " + key);
                }
            });
        }
        return values;
    }

    /** 转义扩展方的配置文本，避免生成非法 Java 字符串。 */
    private static String javaString(final String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\b", "\\b").replace("\f", "\\f").replace("\t", "\\t")
                .replace("\r", "\\r").replace("\n", "\\n") + '"';
    }

    /** 转义 properties 特殊字符，保留路径、换行和首尾空格的原值。 */
    private static String propertyText(final String value) {
        return value.replace("\\", "\\\\").replace("\f", "\\f").replace("\t", "\\t")
                .replace("\r", "\\r").replace("\n", "\\n").replace(" ", "\\ ")
                .replace("=", "\\=").replace(":", "\\:").replace("#", "\\#").replace("!", "\\!");
    }
}
