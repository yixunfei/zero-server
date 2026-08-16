package group.zn.zero.runtime.config;

import group.zn.zero.runtime.api.ComponentId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 仅暴露当前组件 schema 中 typed 值的只读视图。
 *
 * @author zn
 */
public final class ComponentConfig {

    private final ComponentId owner;
    private final Map<ConfigKey<?>, Object> values;
    private final List<String> logicalKeys;

    ComponentConfig(final ComponentId owner, final Map<ConfigKey<?>, Object> values) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.values = Map.copyOf(Objects.requireNonNull(values, "values"));
        this.logicalKeys = this.values.keySet().stream()
                .map(ConfigKey::logicalName)
                .sorted()
                .toList();
    }

    public ComponentId owner() {
        return owner;
    }

    public <T> T require(final ConfigKey<T> key) {
        ConfigKey<T> checked = ownedKey(key);
        Object value = values.get(checked);
        if (value == null) {
            throw new IllegalArgumentException("required component config is absent: " + checked.logicalName());
        }
        return checked.type().cast(value);
    }

    public <T> Optional<T> optional(final ConfigKey<T> key) {
        ConfigKey<T> checked = ownedKey(key);
        return Optional.ofNullable(values.get(checked)).map(checked.type()::cast);
    }

    public List<String> logicalKeys() {
        return new ArrayList<>(logicalKeys);
    }

    @Override
    public String toString() {
        return "ComponentConfig{owner=" + owner + ", keys=" + logicalKeys + '}';
    }

    private <T> ConfigKey<T> ownedKey(final ConfigKey<T> key) {
        ConfigKey<T> checked = Objects.requireNonNull(key, "key");
        if (!owner.equals(checked.owner())) {
            throw new IllegalArgumentException("config key is not owned by component " + owner);
        }
        return checked;
    }
}
