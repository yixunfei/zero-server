package group.zn.zero.runtime.assembly;

import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.ComponentSetKey;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyException;
import group.zn.zero.runtime.diagnostics.RuntimeErrorCode;
import group.zn.zero.runtime.diagnostics.RuntimeFailurePhase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单值选择和多值贡献者选择的不可变快照。
 *
 * @author zn
 */
public final class RuntimeSelection {

    private final Map<ComponentKey<?>, Decision> singles;
    private final Map<ComponentSetKey<?>, Map<ComponentId, Decision>> multiples;

    private RuntimeSelection(final Builder builder) {
        singles = Collections.unmodifiableMap(new LinkedHashMap<>(builder.singles));
        Map<ComponentSetKey<?>, Map<ComponentId, Decision>> copied = new LinkedHashMap<>();
        builder.multiples.forEach((key, decisions) -> copied.put(
                key, Collections.unmodifiableMap(new LinkedHashMap<>(decisions))));
        multiples = Collections.unmodifiableMap(copied);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static RuntimeSelection empty() {
        return builder().build();
    }

    Map<ComponentKey<?>, Decision> singles() {
        return singles;
    }

    Map<ComponentSetKey<?>, Map<ComponentId, Decision>> multiples() {
        return multiples;
    }

    boolean hasSelection(final group.zn.zero.runtime.api.BindingKey<?> key) {
        if (key instanceof ComponentKey<?> single) {
            return singles.containsKey(single);
        }
        ComponentSetKey<?> multiple = (ComponentSetKey<?>) key;
        return multiples.containsKey(multiple) && !multiples.get(multiple).isEmpty();
    }

    @Override
    public String toString() {
        return "RuntimeSelection{singleKeys=" + singles.keySet() + ", multipleKeys=" + multiples.keySet() + '}';
    }

    /** 当前生效的 provider 选择。 */
    record Decision(ComponentId providerId, SelectionSource source, ComponentId previousProviderId) {

        Decision {
            providerId = Objects.requireNonNull(providerId, "providerId");
            source = Objects.requireNonNull(source, "source");
        }
    }

    /**
     * RuntimeSelection builder；不同 provider 的单值选择必须显式 override。
     *
     * @author zn
     */
    public static final class Builder {

        private final Map<ComponentKey<?>, Decision> singles = new LinkedHashMap<>();
        private final Map<ComponentSetKey<?>, Map<ComponentId, Decision>> multiples = new LinkedHashMap<>();

        private Builder() {
        }

        public <T> Builder select(
                final ComponentKey<T> key,
                final ComponentId providerId,
                final SelectionSource source) {
            ComponentKey<T> checkedKey = Objects.requireNonNull(key, "key");
            Decision decision = new Decision(providerId, source, null);
            Decision existing = singles.putIfAbsent(checkedKey, decision);
            if (existing != null && !existing.providerId().equals(providerId)) {
                throw RuntimeAssemblyException.failure(
                        RuntimeErrorCode.RUNTIME_AMBIGUOUS_CAPABILITY,
                        RuntimeFailurePhase.PLANNING,
                        null,
                        "key=" + checkedKey.id());
            }
            return this;
        }

        public <T> Builder override(
                final ComponentKey<T> key,
                final ComponentId providerId,
                final String sourceId) {
            ComponentKey<T> checkedKey = Objects.requireNonNull(key, "key");
            Decision previous = singles.get(checkedKey);
            ComponentId previousProvider = previous == null ? null : previous.providerId();
            singles.put(checkedKey, new Decision(
                    providerId,
                    new SelectionSource(SelectionSourceKind.OVERRIDE, sourceId),
                    previousProvider));
            return this;
        }

        public <T> Builder contribute(
                final ComponentSetKey<T> key,
                final ComponentId providerId,
                final SelectionSource source) {
            ComponentSetKey<T> checkedKey = Objects.requireNonNull(key, "key");
            Map<ComponentId, Decision> providers = multiples.computeIfAbsent(
                    checkedKey, ignored -> new LinkedHashMap<>());
            providers.putIfAbsent(
                    Objects.requireNonNull(providerId, "providerId"),
                    new Decision(providerId, source, null));
            return this;
        }

        public Builder merge(final RuntimeSelection selection) {
            RuntimeSelection checked = Objects.requireNonNull(selection, "selection");
            checked.singles.forEach((key, decision) -> selectUnchecked(key, decision));
            checked.multiples.forEach((key, decisions) -> decisions.values()
                    .forEach(decision -> contributeUnchecked(key, decision)));
            return this;
        }

        public RuntimeSelection build() {
            sortSelections();
            return new RuntimeSelection(this);
        }

        private void selectUnchecked(final ComponentKey<?> key, final Decision decision) {
            Decision existing = singles.putIfAbsent(key, decision);
            if (existing != null && !existing.providerId().equals(decision.providerId())) {
                throw RuntimeAssemblyException.failure(
                        RuntimeErrorCode.RUNTIME_AMBIGUOUS_CAPABILITY,
                        RuntimeFailurePhase.PLANNING,
                        null,
                        "key=" + key.id());
            }
        }

        private void contributeUnchecked(final ComponentSetKey<?> key, final Decision decision) {
            multiples.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                    .putIfAbsent(decision.providerId(), decision);
        }

        private void sortSelections() {
            List<Map.Entry<ComponentKey<?>, Decision>> singleEntries = new ArrayList<>(singles.entrySet());
            singleEntries.sort(Map.Entry.comparingByKey(java.util.Comparator
                    .comparing((ComponentKey<?> key) -> key.id())
                    .thenComparing(key -> key.type().getName())));
            singles.clear();
            singleEntries.forEach(entry -> singles.put(entry.getKey(), entry.getValue()));

            List<Map.Entry<ComponentSetKey<?>, Map<ComponentId, Decision>>> multipleEntries =
                    new ArrayList<>(multiples.entrySet());
            multipleEntries.sort(Map.Entry.comparingByKey(java.util.Comparator
                    .comparing((ComponentSetKey<?> key) -> key.id())
                    .thenComparing(key -> key.type().getName())));
            multiples.clear();
            multipleEntries.forEach(entry -> {
                Map<ComponentId, Decision> decisions = entry.getValue();
                List<Decision> ordered = new ArrayList<>(decisions.values());
                ordered.sort((left, right) -> left.providerId().compareTo(right.providerId()));
                Map<ComponentId, Decision> result = new LinkedHashMap<>();
                ordered.forEach(decision -> result.put(decision.providerId(), decision));
                multiples.put(entry.getKey(), Collections.unmodifiableMap(result));
            });
        }
    }
}
