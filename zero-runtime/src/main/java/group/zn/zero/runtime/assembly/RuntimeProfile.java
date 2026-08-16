package group.zn.zero.runtime.assembly;

import group.zn.zero.runtime.api.BindingKey;
import group.zn.zero.runtime.internal.RuntimeIdentifiers;
import group.zn.zero.runtime.spi.ComponentKind;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 只校验拓扑、不隐式启用组件的运行政策。
 *
 * @author zn
 */
public final class RuntimeProfile {

    private final String name;
    private final Set<ComponentKind> forbiddenKinds;
    private final Set<ComponentKind> startupHealthRequiredKinds;
    private final Map<BindingKey<?>, Set<ComponentKind>> forbiddenKindsByCapability;

    private RuntimeProfile(final Builder builder) {
        name = builder.name;
        forbiddenKinds = Set.copyOf(builder.forbiddenKinds);
        startupHealthRequiredKinds = Set.copyOf(builder.startupHealthRequiredKinds);
        Map<BindingKey<?>, Set<ComponentKind>> copied = new LinkedHashMap<>();
        builder.forbiddenKindsByCapability.forEach((key, value) -> copied.put(key, Set.copyOf(value)));
        forbiddenKindsByCapability = Map.copyOf(copied);
    }

    public static Builder builder(final String name) {
        return new Builder(name);
    }

    public static RuntimeProfile permissive(final String name) {
        return builder(name).build();
    }

    public static RuntimeProfile minimal() {
        return builder("minimal").forbidKind(ComponentKind.EXTERNAL).build();
    }

    public static RuntimeProfile local() {
        return builder("local").forbidKind(ComponentKind.EXTERNAL).build();
    }

    public static RuntimeProfile standalone() {
        return builder("standalone").build();
    }

    public static RuntimeProfile externalTest() {
        return builder("external-test").requireStartupHealth(ComponentKind.EXTERNAL).build();
    }

    public static RuntimeProfile production(final Set<? extends BindingKey<?>> criticalCapabilities) {
        Builder builder = builder("production").requireStartupHealth(ComponentKind.EXTERNAL);
        Objects.requireNonNull(criticalCapabilities, "criticalCapabilities")
                .forEach(key -> builder.forbidKindFor(key, ComponentKind.LOCAL));
        return builder.build();
    }

    public String name() {
        return name;
    }

    boolean allows(final ComponentKind kind) {
        return !forbiddenKinds.contains(kind);
    }

    boolean requiresStartupHealth(final ComponentKind kind) {
        return startupHealthRequiredKinds.contains(kind);
    }

    boolean allowsFor(final BindingKey<?> key, final ComponentKind kind) {
        return !forbiddenKindsByCapability.getOrDefault(key, Set.of()).contains(kind);
    }

    @Override
    public String toString() {
        return "RuntimeProfile{name=" + name + ", forbiddenKinds=" + forbiddenKinds
                + ", startupHealthRequiredKinds=" + startupHealthRequiredKinds
                + ", constrainedCapabilities=" + forbiddenKindsByCapability.keySet() + '}';
    }

    /** RuntimeProfile builder。 */
    public static final class Builder {

        private final String name;
        private final Set<ComponentKind> forbiddenKinds = EnumSet.noneOf(ComponentKind.class);
        private final Set<ComponentKind> startupHealthRequiredKinds = EnumSet.noneOf(ComponentKind.class);
        private final Map<BindingKey<?>, Set<ComponentKind>> forbiddenKindsByCapability = new LinkedHashMap<>();

        private Builder(final String name) {
            this.name = RuntimeIdentifiers.requireStableId(name, "profileName");
        }

        public Builder forbidKind(final ComponentKind kind) {
            forbiddenKinds.add(Objects.requireNonNull(kind, "kind"));
            return this;
        }

        public Builder requireStartupHealth(final ComponentKind kind) {
            startupHealthRequiredKinds.add(Objects.requireNonNull(kind, "kind"));
            return this;
        }

        public Builder forbidKindFor(final BindingKey<?> key, final ComponentKind kind) {
            forbiddenKindsByCapability
                    .computeIfAbsent(Objects.requireNonNull(key, "key"), ignored -> EnumSet.noneOf(ComponentKind.class))
                    .add(Objects.requireNonNull(kind, "kind"));
            return this;
        }

        public RuntimeProfile build() {
            return new RuntimeProfile(this);
        }
    }
}
