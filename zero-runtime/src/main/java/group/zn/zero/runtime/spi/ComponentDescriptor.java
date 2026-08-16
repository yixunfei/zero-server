package group.zn.zero.runtime.spi;

import group.zn.zero.runtime.api.BindingKey;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.config.ConfigSchema;
import group.zn.zero.runtime.health.HealthPhase;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * runtime component 的不可变能力、依赖、配置和生命周期声明。
 *
 * @author zn
 */
public final class ComponentDescriptor {

    private static final Comparator<BindingKey<?>> KEY_ORDER = Comparator
            .comparing((BindingKey<?> key) -> key.id())
            .thenComparing(key -> key.cardinality().name())
            .thenComparing(key -> key.type().getName());

    private final ComponentId id;
    private final Set<BindingKey<?>> provides;
    private final Set<BindingKey<?>> requires;
    private final Set<BindingKey<?>> optional;
    private final Set<ComponentId> conflictsWith;
    private final Set<ComponentId> startAfter;
    private final ConfigSchema configSchema;
    private final ComponentKind kind;
    private final Set<HealthPhase> healthPhases;

    private ComponentDescriptor(final Builder builder) {
        id = builder.id;
        provides = sortedKeys(builder.provides);
        requires = sortedKeys(builder.requires);
        optional = sortedKeys(builder.optional);
        conflictsWith = sortedIds(builder.conflictsWith);
        startAfter = sortedIds(builder.startAfter);
        configSchema = builder.configSchema;
        kind = builder.kind;
        healthPhases = Set.copyOf(builder.healthPhases);
        validate();
    }

    public static Builder builder(final ComponentId id) {
        return new Builder(id);
    }

    public ComponentId id() {
        return id;
    }

    public Set<BindingKey<?>> provides() {
        return provides;
    }

    public Set<BindingKey<?>> requires() {
        return requires;
    }

    public Set<BindingKey<?>> optional() {
        return optional;
    }

    public Set<ComponentId> conflictsWith() {
        return conflictsWith;
    }

    public Set<ComponentId> startAfter() {
        return startAfter;
    }

    public ConfigSchema configSchema() {
        return configSchema;
    }

    public ComponentKind kind() {
        return kind;
    }

    public Set<HealthPhase> healthPhases() {
        return healthPhases;
    }

    @Override
    public String toString() {
        return "ComponentDescriptor{id=" + id + ", provides=" + provides + ", requires=" + requires
                + ", optional=" + optional + ", kind=" + kind + '}';
    }

    private void validate() {
        if (provides.isEmpty()) {
            throw new IllegalArgumentException("component descriptor must provide at least one capability");
        }
        if (!configSchema.owner().equals(id)) {
            throw new IllegalArgumentException("component config schema owner does not match descriptor id");
        }
        if (conflictsWith.contains(id) || startAfter.contains(id)) {
            throw new IllegalArgumentException("component cannot conflict with or start after itself");
        }
        Set<BindingKey<?>> dependencyOverlap = new LinkedHashSet<>(requires);
        dependencyOverlap.retainAll(optional);
        if (!dependencyOverlap.isEmpty()) {
            throw new IllegalArgumentException("required and optional capabilities must be disjoint");
        }
    }

    private static Set<BindingKey<?>> sortedKeys(final Collection<BindingKey<?>> source) {
        List<BindingKey<?>> ordered = new ArrayList<>(source);
        ordered.sort(KEY_ORDER);
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(ordered));
    }

    private static Set<ComponentId> sortedIds(final Collection<ComponentId> source) {
        List<ComponentId> ordered = new ArrayList<>(source);
        ordered.sort(ComponentId::compareTo);
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(ordered));
    }

    /**
     * ComponentDescriptor builder。
     *
     * @author zn
     */
    public static final class Builder {

        private final ComponentId id;
        private final Set<BindingKey<?>> provides = new LinkedHashSet<>();
        private final Set<BindingKey<?>> requires = new LinkedHashSet<>();
        private final Set<BindingKey<?>> optional = new LinkedHashSet<>();
        private final Set<ComponentId> conflictsWith = new LinkedHashSet<>();
        private final Set<ComponentId> startAfter = new LinkedHashSet<>();
        private ConfigSchema configSchema;
        private ComponentKind kind = ComponentKind.FOUNDATION;
        private final Set<HealthPhase> healthPhases = new LinkedHashSet<>();

        private Builder(final ComponentId id) {
            this.id = Objects.requireNonNull(id, "id");
            this.configSchema = ConfigSchema.empty(id);
        }

        public Builder provide(final BindingKey<?> key) {
            provides.add(Objects.requireNonNull(key, "key"));
            return this;
        }

        public Builder require(final BindingKey<?> key) {
            requires.add(Objects.requireNonNull(key, "key"));
            return this;
        }

        public Builder optional(final BindingKey<?> key) {
            optional.add(Objects.requireNonNull(key, "key"));
            return this;
        }

        public Builder conflictWith(final ComponentId componentId) {
            conflictsWith.add(Objects.requireNonNull(componentId, "componentId"));
            return this;
        }

        public Builder startAfter(final ComponentId componentId) {
            startAfter.add(Objects.requireNonNull(componentId, "componentId"));
            return this;
        }

        public Builder configSchema(final ConfigSchema schema) {
            configSchema = Objects.requireNonNull(schema, "schema");
            return this;
        }

        public Builder kind(final ComponentKind componentKind) {
            kind = Objects.requireNonNull(componentKind, "componentKind");
            return this;
        }

        public Builder health(final HealthPhase phase) {
            healthPhases.add(Objects.requireNonNull(phase, "phase"));
            return this;
        }

        public ComponentDescriptor build() {
            return new ComponentDescriptor(this);
        }
    }
}
