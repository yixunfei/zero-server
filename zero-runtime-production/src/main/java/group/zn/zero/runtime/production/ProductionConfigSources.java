package group.zn.zero.runtime.production;

import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.config.ConfigSource;
import group.zn.zero.runtime.config.ConfigSourceKind;
import group.zn.zero.runtime.config.MapConfigSource;
import group.zn.zero.runtime.config.ConfigSourceOrigin;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 把已按 Production 优先级解析的配置安全地桥接到 typed component schema。 */
public final class ProductionConfigSources {

    private static final List<ConfigSourceKind> SOURCE_ORDER = List.of(
            ConfigSourceKind.PROGRAMMATIC,
            ConfigSourceKind.SYSTEM_PROPERTY,
            ConfigSourceKind.ENVIRONMENT, ConfigSourceKind.FILE, ConfigSourceKind.DEFAULT);

    private ProductionConfigSources() {
    }

    public static List<ConfigSource> from(
            final ComponentId owner,
            final List<ResolvedProductionSetting> settings) {
        ComponentId checkedOwner = Objects.requireNonNull(owner, "owner");
        Map<ConfigSourceKind, Map<String, String>> valuesByKind = new EnumMap<>(ConfigSourceKind.class);
        for (ResolvedProductionSetting setting : Objects.requireNonNull(settings, "settings")) {
            addSetting(valuesByKind, Objects.requireNonNull(setting, "setting"));
        }
        List<ConfigSource> sources = new ArrayList<>();
        for (ConfigSourceKind kind : SOURCE_ORDER) {
            Map<String, String> values = valuesByKind.get(kind);
            if (values != null && !values.isEmpty()) {
                ConfigSource source = new MapConfigSource(
                        kind == ConfigSourceKind.FILE || kind == ConfigSourceKind.DEFAULT
                                ? ConfigSourceKind.PROGRAMMATIC : kind,
                        checkedOwner.value() + '.' + sourceId(kind), values);
                sources.add(new LocatedSource(source, kind));
            }
        }
        return List.copyOf(sources);
    }

    private static void addSetting(
            final Map<ConfigSourceKind, Map<String, String>> valuesByKind,
            final ResolvedProductionSetting setting) {
        if (!setting.present()) {
            return;
        }
        ZeroProductionConfigSource source = setting.source().orElseThrow();
        ConfigSourceKind kind = sourceKind(source.sourceType());
        Map<String, String> values = valuesByKind.computeIfAbsent(kind, ignored -> new LinkedHashMap<>());
        String previous = values.putIfAbsent(source.sourceKey(), setting.require());
        if (previous != null && !previous.equals(setting.require())) {
            throw new IllegalArgumentException("conflicting normalized production config source");
        }
    }

    private static ConfigSourceKind sourceKind(final String sourceType) {
        return switch (Objects.requireNonNull(sourceType, "sourceType")) {
            case "ZeroConfig" -> ConfigSourceKind.PROGRAMMATIC;
            case "systemProperty" -> ConfigSourceKind.SYSTEM_PROPERTY;
            case "environment" -> ConfigSourceKind.ENVIRONMENT;
            case "file" -> ConfigSourceKind.FILE;
            case "default" -> ConfigSourceKind.DEFAULT;
            default -> throw new IllegalArgumentException("unsupported production config source type");
        };
    }

    private static String sourceId(final ConfigSourceKind kind) {
        return switch (kind) {
            case PROGRAMMATIC -> "programmatic";
            case SYSTEM_PROPERTY -> "system-property";
            case ENVIRONMENT -> "environment";
            case FILE -> "file";
            case DEFAULT -> "application-default";
            default -> throw new IllegalArgumentException("unsupported production config source kind");
        };
    }

    /** 保留经 ZeroConfig 桥接的文件/默认值归因；只读且线程安全。 */
    private record LocatedSource(ConfigSource delegate, ConfigSourceKind original) implements ConfigSource {
        @Override
        public ConfigSourceKind kind() {
            return delegate.kind();
        }

        @Override
        public String id() {
            return delegate.id();
        }

        @Override
        public java.util.Optional<String> value(final String alias) {
            return delegate.value(alias);
        }

        @Override
        public ConfigSourceOrigin origin(final String alias) {
            return new ConfigSourceOrigin(original, id(), alias);
        }
    }
}
