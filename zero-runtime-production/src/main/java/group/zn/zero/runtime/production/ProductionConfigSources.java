package group.zn.zero.runtime.production;

import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.config.ConfigSource;
import group.zn.zero.runtime.config.ConfigSourceKind;
import group.zn.zero.runtime.config.MapConfigSource;
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
            ConfigSourceKind.ENVIRONMENT);

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
                sources.add(new MapConfigSource(
                        kind,
                        checkedOwner.value() + '.' + sourceId(kind),
                        values));
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
            default -> throw new IllegalArgumentException("unsupported production config source type");
        };
    }

    private static String sourceId(final ConfigSourceKind kind) {
        return switch (kind) {
            case PROGRAMMATIC -> "programmatic";
            case SYSTEM_PROPERTY -> "system-property";
            case ENVIRONMENT -> "environment";
            default -> throw new IllegalArgumentException("unsupported production config source kind");
        };
    }
}
