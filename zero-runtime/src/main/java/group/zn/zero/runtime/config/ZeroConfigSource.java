package group.zn.zero.runtime.config;

import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.runtime.internal.RuntimeIdentifiers;
import java.util.Objects;
import java.util.Optional;

/**
 * 把现有 ZeroConfig 显式接入 typed schema 的只读适配器。
 *
 * @author zn
 */
public final class ZeroConfigSource implements ConfigSource {

    private final String id;
    private final ZeroConfig config;

    public ZeroConfigSource(final String id, final ZeroConfig config) {
        this.id = RuntimeIdentifiers.requireStableId(id, "sourceId");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public ConfigSourceKind kind() {
        return ConfigSourceKind.PROGRAMMATIC;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Optional<String> value(final String alias) {
        return config.get(RuntimeIdentifiers.requireSafeAlias(alias, "sourceAlias"));
    }

    /** 保留输入快照的原始归因；不读取环境、不公开路径或值，线程安全性同输入。 */
    @Override
    public ConfigSourceOrigin origin(final String alias) {
        ConfigSourceKind origin = ConfigSourceKind.valueOf(config.origin(alias).name());
        return new ConfigSourceOrigin(origin, id, origin == ConfigSourceKind.ENVIRONMENT
                ? group.zn.zero.core.config.ConfigLookup.environmentAlias(alias) : alias);
    }

    @Override
    public String toString() {
        return "ZeroConfigSource{id=" + id + '}';
    }
}
