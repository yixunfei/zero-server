package group.zn.zero.codegen.scaffold;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 按所选 Adapter 生成启用配置和外部配置样例，不把地址或凭据固化进业务源码。 */
final class ScaffoldConfiguration {

    private ScaffoldConfiguration() {
    }

    /** 生成 Java 默认配置片段；仅包含模式和显式开关，返回不可变字符串。 */
    static String defaults(final ScaffoldComponents.Selection selection) {
        return switches(selection).entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\", \"" + entry.getValue() + "\"")
                .collect(Collectors.joining(",\n                "));
    }

    /** 生成 properties 样例；不读取环境、创建客户端或包含真实凭据。 */
    static String example(final ProjectScaffoldRequest request, final ScaffoldComponents.Selection selection) {
        StringBuilder result = new StringBuilder("zero.name=" + request.projectName() + "\n");
        switches(selection).forEach((key, value) -> result.append(key).append('=').append(value).append('\n'));
        for (String component : selection.components()) {
            result.append(settings(component, request.projectName()));
        }
        return result.toString();
    }

    private static Map<String, String> switches(final ScaffoldComponents.Selection selection) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("zero.mode", selection.external() ? "external-test" : "local");
        for (String component : selection.components()) {
            if (List.of("redis", "mongo", "postgresql").contains(component)) {
                values.put("zero.adapter.data." + component + ".enabled", "true");
            } else if (component.equals("kafka")) {
                values.put("zero.adapter.rpc.kafka.enabled", "true");
            } else if (component.equals("nacos")) {
                values.put("zero.discovery.mode", "nacos");
            }
        }
        return values;
    }

    private static String settings(final String component, final String name) {
        return switch (component) {
            case "redis" -> "zero.redis.uri=redis://127.0.0.1:6379\n";
            case "mongo" -> "zero.mongo.uri=mongodb://127.0.0.1:27017\nzero.mongo.database=game\n";
            case "postgresql" -> "zero.postgresql.url=jdbc:postgresql://127.0.0.1:5432/game\n"
                    + "zero.postgresql.username=\nzero.postgresql.password=\nzero.postgresql.table=zero_data_object\n";
            case "kafka" -> "zero.rpc.kafka.bootstrap-servers=127.0.0.1:9092\n"
                    + "# Each process needs its own client ID, consumer group and reply topic.\n"
                    + "zero.rpc.kafka.client-id=" + name + "\nzero.rpc.kafka.consumer-group-id=" + name + "\n"
                    + "zero.rpc.kafka.topic-prefix=game\nzero.rpc.kafka.reply-topic=game.reply." + name + "\n";
            case "nacos" -> "zero.discovery.nacos.server-addr=127.0.0.1:8848\n"
                    + "zero.discovery.nacos.namespace=game\nzero.discovery.nacos.default-group=DEFAULT_GROUP\n"
                    + "zero.discovery.nacos.default-cluster=DEFAULT\n";
            default -> "";
        };
    }
}
