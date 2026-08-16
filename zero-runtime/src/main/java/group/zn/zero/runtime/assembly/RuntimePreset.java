package group.zn.zero.runtime.assembly;

import group.zn.zero.runtime.api.BindingKey;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.ComponentSetKey;
import group.zn.zero.runtime.internal.RuntimeIdentifiers;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 不探测环境、不改变 profile policy 的命名选择清单。
 *
 * @author zn
 */
public final class RuntimePreset {

    private final String name;
    private final Set<BindingKey<?>> requirements;
    private final RuntimeSelection selection;

    private RuntimePreset(final Builder builder) {
        name = builder.name;
        List<BindingKey<?>> ordered = new ArrayList<>(builder.requirements);
        ordered.sort(Comparator.comparing(BindingKey::id));
        requirements = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(ordered));
        selection = builder.selection.build();
    }

    public static Builder builder(final String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public Set<BindingKey<?>> requirements() {
        return requirements;
    }

    public RuntimeSelection selection() {
        return selection;
    }

    @Override
    public String toString() {
        return "RuntimePreset{name=" + name + ", requirements=" + requirements + ", selection=" + selection + '}';
    }

    /** RuntimePreset builder。 */
    public static final class Builder {

        private final String name;
        private final Set<BindingKey<?>> requirements = new LinkedHashSet<>();
        private final RuntimeSelection.Builder selection = RuntimeSelection.builder();
        private final SelectionSource source;

        private Builder(final String name) {
            this.name = RuntimeIdentifiers.requireStableId(name, "presetName");
            this.source = new SelectionSource(SelectionSourceKind.PRESET, this.name);
        }

        public Builder require(final BindingKey<?> key) {
            requirements.add(Objects.requireNonNull(key, "key"));
            return this;
        }

        public <T> Builder select(final ComponentKey<T> key, final ComponentId providerId) {
            selection.select(key, providerId, source);
            return this;
        }

        public <T> Builder contribute(final ComponentSetKey<T> key, final ComponentId providerId) {
            selection.contribute(key, providerId, source);
            return this;
        }

        public RuntimePreset build() {
            return new RuntimePreset(this);
        }
    }
}
