package group.zn.zero.core.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 Map 的不可变配置实现。
 *
 * @author zn
 */
public final class MapZeroConfig implements ZeroConfig {

    /**
     * 配置项快照。
     */
    private final Map<String, String> values;

    /**
     * 创建配置快照。
     *
     * @param values 配置值；不可为空。
     * @throws NullPointerException 当配置值为空时抛出。
     */
    public MapZeroConfig(final Map<String, String> values) {
        this.values = Map.copyOf(new LinkedHashMap<>(java.util.Objects.requireNonNull(values, "values")));
    }

    /**
     * 根据配置键读取字符串值。
     *
     * @param key 配置键；不可为空。
     * @return 配置值；为空表示不存在；线程安全。
     */
    @Override
    public Optional<String> get(final String key) {
        return Optional.ofNullable(values.get(key));
    }

    /**
     * 返回当前配置快照。
     *
     * @return 不可变配置快照；不可为空；无承诺顺序；线程安全。
     */
    @Override
    public Map<String, String> asMap() {
        return values;
    }
}

