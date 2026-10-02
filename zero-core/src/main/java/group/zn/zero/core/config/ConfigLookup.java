package group.zn.zero.core.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** 共享候选读取契约：显式配置/文件、系统属性、环境变量、应用默认值。@author zn */
public final class ConfigLookup {
    /** 输入快照。 */
    private final ZeroConfig config;
    /** JVM 来源。 */
    private final Function<String, String> properties;
    /** 环境来源。 */
    private final Function<String, String> environment;
    /** 同次解析中冻结已读属性，包括缺失。 */
    private final Map<String, Optional<String>> propertyValues = new HashMap<>();
    /** 同次解析中冻结已读环境，包括缺失。 */
    private final Map<String, Optional<String>> environmentValues = new HashMap<>();

    /**
     * 创建串行解析会话；不读取环境或修改输入，实例非线程安全。
     * @param config 输入快照，不可为空。
     * @param properties 系统属性查询，不可为空。
     * @param environment 环境变量查询，不可为空。
     * @throws NullPointerException 输入为空时抛出。
     */
    public ConfigLookup(final ZeroConfig config, final Function<String, String> properties,
            final Function<String, String> environment) {
        this.config = SourcedZeroConfig.withDefaults(Objects.requireNonNull(config, "config"), Map.of());
        this.properties = Objects.requireNonNull(properties, "properties");
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /**
     * 读取第一个存在值（包含空白）；不解码、不回退非法值，不修改输入。
     * @param keys 配置键候选，按顺序读取，不可含 null。
     * @param systemKeys 系统属性候选，按顺序读取，不可含 null。
     * @param environmentKeys 环境变量候选，按顺序读取，不可含 null。
     * @return 值与来源；无候选时为空，调用线程须串行使用本会话。
     * @throws NullPointerException 输入或元素为空时抛出。
     */
    public Optional<Value> find(final List<String> keys, final List<String> systemKeys,
            final List<String> environmentKeys) {
        List<String> checked = List.copyOf(keys);
        Optional<Value> explicit = configured(checked, false);
        if (explicit.isPresent()) {
            return explicit;
        }
        Optional<Value> system = external(List.copyOf(systemKeys), properties, propertyValues, ConfigOrigin.SYSTEM_PROPERTY);
        if (system.isPresent()) {
            return system;
        }
        Optional<Value> variables = external(List.copyOf(environmentKeys), environment, environmentValues, ConfigOrigin.ENVIRONMENT);
        return variables.isPresent() ? variables : configured(checked, true);
    }

    /**
     * 解析输入中显式列出的键；仅查询这些键，不扫描整个环境。
     * @param config 输入配置，键集合即 allowlist。
     * @param properties 属性读取函数。
     * @param environment 环境读取函数。
     * @return 已冻结值和来源的配置，线程安全、无序、可为空。
     * @throws NullPointerException 输入为空时抛出。
     */
    public static ZeroConfig snapshot(final ZeroConfig config, final Function<String, String> properties,
            final Function<String, String> environment) {
        ConfigLookup lookup = new ConfigLookup(config, properties, environment);
        Map<String, String> values = new HashMap<>();
        Map<String, ConfigOrigin> origins = new HashMap<>();
        config.asMap().keySet().forEach(key -> {
            Value value = lookup.find(List.of(key), List.of(key), List.of(environmentAlias(key))).orElseThrow();
            values.put(key, value.text());
            origins.put(key, value.origin());
        });
        return new SourcedZeroConfig(values, origins);
    }

    /**
     * 生成标准环境键，点和连字符转换为下划线。
     * @param key 逻辑键，不可为空。
     * @return 不可变键名；无副作用且线程安全。
     * @throws NullPointerException key 为空时抛出。
     */
    public static String environmentAlias(final String key) {
        return Objects.requireNonNull(key, "key").replace('.', '_').replace('-', '_')
                .toUpperCase(java.util.Locale.ROOT);
    }

    private Optional<Value> configured(final List<String> keys, final boolean defaults) {
        for (String key : keys) {
            Optional<String> value = config.get(key);
            if (value.isPresent() && (config.origin(key) == ConfigOrigin.DEFAULT) == defaults) {
                return Optional.of(new Value(value.orElseThrow(), config.origin(key), key));
            }
        }
        return Optional.empty();
    }

    private Optional<Value> external(final List<String> keys, final Function<String, String> lookup,
            final Map<String, Optional<String>> cache, final ConfigOrigin origin) {
        for (String key : keys) {
            Optional<String> value = cache.computeIfAbsent(key, alias -> Optional.ofNullable(lookup.apply(alias)));
            if (value.isPresent()) {
                return Optional.of(new Value(value.orElseThrow(), origin, key));
            }
        }
        return Optional.empty();
    }

    /**
     * 同次读取的值和来源，只用于内部消费；文本输出固定脱敏。
     * @param text 原始值。
     * @param origin 来源类别。
     * @param alias 实际读取键。
     * @author zn
     */
    public record Value(String text, ConfigOrigin origin, String alias) {
        /** 冻结字段，线程安全；空输入抛 NullPointerException。 */
        public Value {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(alias, "alias");
        }

        /** 返回脱敏摘要；无副作用且线程安全。@return 不包含原始值的摘要。 */
        @Override
        public String toString() {
            return "ConfigValue{origin=" + origin + ", value=***}";
        }
    }
}
