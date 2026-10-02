package group.zn.zero.runtime.config;

import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyException;
import group.zn.zero.runtime.diagnostics.RuntimeErrorCode;
import group.zn.zero.runtime.diagnostics.RuntimeFailurePhase;
import group.zn.zero.runtime.internal.RuntimeIdentifiers;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

/**
 * 在任何 provider 创建前解析并校验聚合 schema。
 *
 * @author zn
 */
public final class RuntimeConfigResolver {

    private RuntimeConfigResolver() {
    }

    public static ResolvedRuntimeConfig resolve(
            final Collection<ConfigSchema> schemas,
            final List<ConfigSource> sources) {
        List<ConfigSchema> orderedSchemas = Objects.requireNonNull(schemas, "schemas").stream()
                .sorted((left, right) -> left.owner().compareTo(right.owner()))
                .toList();
        List<ConfigSource> checkedSources = validateSources(sources);
        Map<ComponentId, ComponentConfig> components = new LinkedHashMap<>();
        List<ResolvedConfigMetadata> metadata = new ArrayList<>();
        List<RuntimeConfigDiagnosticEntry> diagnostics = new ArrayList<>();
        List<ConfigIssue> issues = new ArrayList<>();
        for (ConfigSchema schema : orderedSchemas) {
            Map<ConfigKey<?>, Object> values = new LinkedHashMap<>();
            for (ConfigKey<?> key : schema.keys()) {
                resolveKey(key, checkedSources, values, metadata, diagnostics, issues);
            }
            components.put(schema.owner(), new ComponentConfig(schema.owner(), values));
        }
        throwIfInvalid(issues);
        return new ResolvedRuntimeConfig(components, metadata, new RuntimeConfigDiagnostics(diagnostics));
    }

    private static List<ConfigSource> validateSources(final List<ConfigSource> sources) {
        List<ConfigSource> checked = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (ConfigSource source : Objects.requireNonNull(sources, "sources")) {
            try {
                ConfigSource current = new CheckedConfigSource(Objects.requireNonNull(source, "source"));
                if (!identities.add(current.kind() + ":" + current.id())) {
                    throw new IllegalArgumentException("duplicate source identity");
                }
                checked.add(current);
            } catch (Throwable failure) {
                throw RuntimeAssemblyException.failure(
                        RuntimeErrorCode.RUNTIME_CONFIG_INVALID,
                        RuntimeFailurePhase.CONFIGURATION,
                        null,
                        "source=invalid");
            }
        }
        return List.copyOf(checked);
    }

    private static <T> void resolveKey(
            final ConfigKey<T> key,
            final List<ConfigSource> sources,
            final Map<ConfigKey<?>, Object> values,
            final List<ResolvedConfigMetadata> metadata,
            final List<RuntimeConfigDiagnosticEntry> diagnostics,
            final List<ConfigIssue> issues) {
        for (ConfigSource source : sources) {
            String alias = key.aliasFor(source.kind());
            Optional<String> raw;
            try {
                raw = source.value(alias);
            } catch (Throwable failure) {
                issues.add(ConfigIssue.invalid(key, source, alias));
                return;
            }
            if (raw.isEmpty()) {
                continue;
            }
            if (!key.acceptedSources().contains(source.kind())) {
                issues.add(ConfigIssue.invalid(key, source, alias));
                return;
            }
            decodeValue(key, raw.orElseThrow(), source, alias, values, metadata, diagnostics, issues);
            return;
        }
        resolveDefaultOrMissing(key, sources, values, metadata, diagnostics, issues);
    }

    private static <T> void decodeValue(
            final ConfigKey<T> key,
            final String raw,
            final ConfigSource source,
            final String alias,
            final Map<ConfigKey<?>, Object> values,
            final List<ResolvedConfigMetadata> metadata,
            final List<RuntimeConfigDiagnosticEntry> diagnostics,
            final List<ConfigIssue> issues) {
        try {
            T decoded = key.decode(raw);
            if (!key.valid(decoded)) {
                issues.add(ConfigIssue.invalid(key, source, alias));
                return;
            }
            values.put(key, decoded);
            ConfigSourceOrigin origin = source.origin(alias);
            ConfigValidationStatus status = origin.kind() == ConfigSourceKind.DEFAULT
                    ? ConfigValidationStatus.DEFAULTED : ConfigValidationStatus.RESOLVED;
            metadata.add(metadata(key, origin.kind(), origin.id(), origin.alias(), status));
            diagnostics.add(diagnostic(key, origin.kind(), origin.id(), origin.alias(), status, decoded));
        } catch (Throwable failure) {
            issues.add(ConfigIssue.invalid(key, source, alias));
        }
    }

    private static <T> void resolveDefaultOrMissing(
            final ConfigKey<T> key,
            final List<ConfigSource> sources,
            final Map<ConfigKey<?>, Object> values,
            final List<ResolvedConfigMetadata> metadata,
            final List<RuntimeConfigDiagnosticEntry> diagnostics,
            final List<ConfigIssue> issues) {
        Optional<T> defaultValue = key.defaultValue();
        if (defaultValue.isPresent()) {
            T value = defaultValue.orElseThrow();
            try {
                if (!key.valid(value)) {
                    issues.add(ConfigIssue.invalid(key, null, key.logicalName()));
                    return;
                }
            } catch (Throwable failure) {
                issues.add(ConfigIssue.invalid(key, null, key.logicalName()));
                return;
            }
            values.put(key, value);
            metadata.add(metadata(
                    key, ConfigSourceKind.DEFAULT, "schema-default", key.logicalName(),
                    ConfigValidationStatus.DEFAULTED));
            diagnostics.add(diagnostic(key, ConfigSourceKind.DEFAULT, "schema-default", key.logicalName(),
                    ConfigValidationStatus.DEFAULTED, value));
        } else if (key.required()) {
            issues.add(ConfigIssue.missing(key, sources));
        } else {
            metadata.add(metadata(
                    key, ConfigSourceKind.DEFAULT, "none", key.logicalName(), ConfigValidationStatus.ABSENT));
            diagnostics.add(diagnostic(key, ConfigSourceKind.DEFAULT, "none", key.logicalName(),
                    ConfigValidationStatus.ABSENT, null));
        }
    }

    private static RuntimeConfigDiagnosticEntry diagnostic(
            final ConfigKey<?> key,
            final ConfigSourceKind sourceKind,
            final String sourceId,
            final String alias,
            final ConfigValidationStatus status,
            final Object value) {
        Optional<String> displayed = Optional.ofNullable(value).map(ignored -> key.sensitive()
                ? "***" : displayValue(key, ignored));
        return new RuntimeConfigDiagnosticEntry(
                key.owner(), key.logicalName(), key.type().getName(), sourceKind, sourceId,
                alias, status, key.reloadability(), key.sensitive(), displayed);
    }

    private static String displayValue(final ConfigKey<?> key, final Object value) {
        Class<?> type = value.getClass();
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (type == String.class || type == Integer.class || type == Long.class || type == Short.class
                || type == Byte.class || type == Double.class || type == Float.class || type == Boolean.class
                || type == java.math.BigInteger.class || type == java.math.BigDecimal.class
                || type == java.time.Duration.class) {
            return String.valueOf(value);
        }
        return "<" + key.type().getSimpleName() + ">";
    }

    private static ResolvedConfigMetadata metadata(
            final ConfigKey<?> key,
            final ConfigSourceKind sourceKind,
            final String sourceId,
            final String alias,
            final ConfigValidationStatus status) {
        return new ResolvedConfigMetadata(
                key.owner(), key.logicalName(), sourceKind, sourceId, alias,
                key.sensitive(), status, key.reloadability());
    }

    private static void throwIfInvalid(final List<ConfigIssue> issues) {
        if (issues.isEmpty()) {
            return;
        }
        RuntimeErrorCode errorCode = issues.stream().anyMatch(ConfigIssue::invalid)
                ? RuntimeErrorCode.RUNTIME_CONFIG_INVALID
                : RuntimeErrorCode.RUNTIME_CONFIG_MISSING;
        String keys = issues.stream()
                .map(ConfigIssue::safeDescription)
                .sorted()
                .distinct()
                .reduce((left, right) -> left + ',' + right)
                .orElse("unknown");
        throw RuntimeAssemblyException.failure(
                errorCode, RuntimeFailurePhase.CONFIGURATION, null, "keys=" + keys);
    }

    private record ConfigIssue(String logicalKey, boolean invalid, String source) {

        private static ConfigIssue invalid(
                final ConfigKey<?> key,
                final ConfigSource source,
                final String alias) {
            if (source == null) {
                return new ConfigIssue(key.logicalName(), true, "default#" + alias);
            }
            try {
                ConfigSourceOrigin origin = source.origin(alias);
                return new ConfigIssue(key.logicalName(), true, origin.kind() + "/" + origin.id() + "#" + origin.alias());
            } catch (Throwable failure) {
                return new ConfigIssue(key.logicalName(), true, "invalid-source-origin");
            }
        }

        private static ConfigIssue missing(final ConfigKey<?> key, final List<ConfigSource> sources) {
            String candidates = sources.stream().map(source -> source.kind() + "/" + source.id())
                    .distinct().sorted().reduce((left, right) -> left + "," + right).orElse("none");
            return new ConfigIssue(key.logicalName(), false, "checked=" + candidates);
        }

        private String safeDescription() {
            return logicalKey + ':' + (invalid ? "invalid" : "missing") + '@' + source;
        }
    }

    /** 冻结 custom source 的安全元数据，避免解析期间发生漂移。 */
    private static final class CheckedConfigSource implements ConfigSource {

        private final ConfigSource delegate;
        private final ConfigSourceKind kind;
        private final String id;

        private CheckedConfigSource(final ConfigSource delegate) {
            this.delegate = delegate;
            this.kind = Objects.requireNonNull(delegate.kind(), "source.kind");
            if (kind == ConfigSourceKind.DEFAULT) {
                throw new IllegalArgumentException("DEFAULT cannot be an external config source");
            }
            this.id = RuntimeIdentifiers.requireStableId(delegate.id(), "sourceId");
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
            return delegate.value(alias);
        }

        @Override
        public ConfigSourceOrigin origin(final String alias) {
            return delegate.origin(alias);
        }

        @Override
        public String toString() {
            return "CheckedConfigSource{kind=" + kind + ", id=" + id + '}';
        }
    }
}
