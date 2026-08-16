package group.zn.zero.runtime.config;

import group.zn.zero.runtime.internal.RuntimeIdentifiers;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于不可变 Map 的显式配置来源。
 *
 * @author zn
 */
public final class MapConfigSource implements ConfigSource {

    private final ConfigSourceKind kind;
    private final String id;
    private final Map<String, String> values;

    public MapConfigSource(
            final ConfigSourceKind kind,
            final String id,
            final Map<String, String> values) {
        this.kind = requireExternalKind(kind);
        this.id = RuntimeIdentifiers.requireStableId(id, "sourceId");
        Map<String, String> checked = new LinkedHashMap<>();
        Objects.requireNonNull(values, "values").forEach((alias, value) -> checked.put(
                RuntimeIdentifiers.requireSafeAlias(alias, "sourceAlias"),
                Objects.requireNonNull(value, "sourceValue")));
        this.values = Map.copyOf(checked);
    }

    @Override
    public ConfigSourceKind kind() {
        return kind;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Optional<String> value(final String alias) {
        return Optional.ofNullable(values.get(RuntimeIdentifiers.requireSafeAlias(alias, "sourceAlias")));
    }

    @Override
    public String toString() {
        return "MapConfigSource{kind=" + kind + ", id=" + id + ", entries=" + values.size() + '}';
    }

    private static ConfigSourceKind requireExternalKind(final ConfigSourceKind sourceKind) {
        ConfigSourceKind checked = Objects.requireNonNull(sourceKind, "kind");
        if (checked == ConfigSourceKind.DEFAULT) {
            throw new IllegalArgumentException("DEFAULT cannot be an external config source");
        }
        return checked;
    }
}
