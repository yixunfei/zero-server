package group.zn.zero.runtime.config;

import group.zn.zero.runtime.api.ComponentId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 单个组件拥有的不可变 typed 配置 schema。
 *
 * @author zn
 */
public final class ConfigSchema {

    /** schema owner。 */
    private final ComponentId owner;

    /** 按逻辑 key 稳定排序的配置项。 */
    private final List<ConfigKey<?>> keys;

    private ConfigSchema(final ComponentId owner, final List<ConfigKey<?>> keys) {
        this.owner = owner;
        this.keys = List.copyOf(keys);
    }

    public static Builder builder(final ComponentId owner) {
        return new Builder(owner);
    }

    public static ConfigSchema empty(final ComponentId owner) {
        return new ConfigSchema(Objects.requireNonNull(owner, "owner"), List.of());
    }

    public ComponentId owner() {
        return owner;
    }

    public List<ConfigKey<?>> keys() {
        return keys;
    }

    /**
     * ConfigSchema builder。
     *
     * @author zn
     */
    public static final class Builder {

        private final ComponentId owner;
        private final Map<String, ConfigKey<?>> keys = new TreeMap<>();

        private Builder(final ComponentId owner) {
            this.owner = Objects.requireNonNull(owner, "owner");
        }

        public Builder add(final ConfigKey<?> key) {
            ConfigKey<?> checked = Objects.requireNonNull(key, "key");
            if (!owner.equals(checked.owner())) {
                throw new IllegalArgumentException("config key owner does not match schema owner");
            }
            if (keys.putIfAbsent(checked.logicalName(), checked) != null) {
                throw new IllegalArgumentException("duplicate config key in schema");
            }
            return this;
        }

        public ConfigSchema build() {
            return new ConfigSchema(owner, new ArrayList<>(keys.values()));
        }
    }
}
