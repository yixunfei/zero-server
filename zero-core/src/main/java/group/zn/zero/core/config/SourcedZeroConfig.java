package group.zn.zero.core.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 值与来源一起冻结的配置快照；不输出值，构造后线程安全。@author zn */
public final class SourcedZeroConfig implements ZeroConfig {
    /** 不可变值映射。 */
    private final Map<String, String> values;
    /** 与值完全对应的来源映射。 */
    private final Map<String, ConfigOrigin> origins;

    /**
     * 冻结配置和来源，不修改输入。
     * @param values 配置值；不可为空或包含 null。
     * @param origins 每个值的来源；键集合必须与 values 一致。
     * @throws NullPointerException 输入或元素为空时抛出。
     * @throws IllegalArgumentException 来源与值不对应时抛出。
     */
    public SourcedZeroConfig(final Map<String, String> values, final Map<String, ConfigOrigin> origins) {
        this.values = Map.copyOf(values);
        this.origins = Map.copyOf(origins);
        if (!this.values.keySet().equals(this.origins.keySet())) {
            throw new IllegalArgumentException("config values and origins must have identical keys");
        }
    }

    /**
     * 为配置补齐缺省值并保留已有来源；无外部副作用。
     * @param config 原配置，不可为空。
     * @param defaults 缺省值，不可为空。
     * @return 不可变、线程安全的快照；已有值优先。
     * @throws NullPointerException 输入或元素为空时抛出。
     */
    public static ZeroConfig withDefaults(final ZeroConfig config, final Map<String, String> defaults) {
        Objects.requireNonNull(config, "config");
        Map<String, String> values = new LinkedHashMap<>(defaults);
        Map<String, ConfigOrigin> origins = new LinkedHashMap<>();
        defaults.keySet().forEach(key -> origins.put(key, ConfigOrigin.DEFAULT));
        config.asMap().forEach((key, value) -> {
            values.put(key, value);
            origins.put(key, config.origin(key));
        });
        return new SourcedZeroConfig(values, origins);
    }

    /** {@inheritDoc} 返回不可变字符串，可跨线程读取。 */
    @Override
    public Optional<String> get(final String key) {
        return Optional.ofNullable(values.get(Objects.requireNonNull(key, "key")));
    }

    /** {@inheritDoc} 返回不可变、无序、可为空且线程安全的映射。 */
    @Override
    public Map<String, String> asMap() {
        return values;
    }

    /** {@inheritDoc} 无副作用且线程安全。 */
    @Override
    public ConfigOrigin origin(final String key) {
        return origins.getOrDefault(Objects.requireNonNull(key, "key"), ConfigOrigin.PROGRAMMATIC);
    }
}
