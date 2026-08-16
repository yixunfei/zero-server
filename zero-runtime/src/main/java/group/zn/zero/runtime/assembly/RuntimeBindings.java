package group.zn.zero.runtime.assembly;

import group.zn.zero.runtime.api.BindingKey;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.ComponentSetKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 装配期可变、提交后不可变的 typed binding 索引。
 */
final class RuntimeBindings {

    private final Map<ComponentKey<?>, Object> singles;
    private final Map<ComponentSetKey<?>, List<Object>> multiples;

    private RuntimeBindings(
            final Map<ComponentKey<?>, Object> singles,
            final Map<ComponentSetKey<?>, List<Object>> multiples) {
        this.singles = Map.copyOf(singles);
        Map<ComponentSetKey<?>, List<Object>> copied = new LinkedHashMap<>();
        multiples.forEach((key, values) -> copied.put(key, List.copyOf(values)));
        this.multiples = Map.copyOf(copied);
    }

    static Mutable mutable() {
        return new Mutable();
    }

    <T> T require(final ComponentKey<T> key) {
        ComponentKey<T> checked = Objects.requireNonNull(key, "key");
        Object value = singles.get(checked);
        if (value == null) {
            throw new IllegalArgumentException("runtime capability is not bound: " + checked.id());
        }
        return checked.type().cast(value);
    }

    <T> Optional<T> optional(final ComponentKey<T> key) {
        ComponentKey<T> checked = Objects.requireNonNull(key, "key");
        return Optional.ofNullable(singles.get(checked)).map(checked.type()::cast);
    }

    <T> List<T> requireAll(final ComponentSetKey<T> key) {
        ComponentSetKey<T> checked = Objects.requireNonNull(key, "key");
        return multiples.getOrDefault(checked, List.of()).stream()
                .map(checked.type()::cast)
                .toList();
    }

    /** 装配事务内的可变索引。 */
    static final class Mutable {

        private final Map<ComponentKey<?>, Object> singles = new LinkedHashMap<>();
        private final Map<ComponentSetKey<?>, List<Object>> multiples = new LinkedHashMap<>();

        <T> T require(final ComponentKey<T> key) {
            ComponentKey<T> checked = Objects.requireNonNull(key, "key");
            Object value = singles.get(checked);
            if (value == null) {
                throw new IllegalStateException("planned required binding is unavailable: " + checked.id());
            }
            return checked.type().cast(value);
        }

        <T> Optional<T> optional(final ComponentKey<T> key) {
            ComponentKey<T> checked = Objects.requireNonNull(key, "key");
            return Optional.ofNullable(singles.get(checked)).map(checked.type()::cast);
        }

        <T> List<T> requireAll(final ComponentSetKey<T> key) {
            ComponentSetKey<T> checked = Objects.requireNonNull(key, "key");
            return multiples.getOrDefault(checked, List.of()).stream()
                    .map(checked.type()::cast)
                    .toList();
        }

        void commit(final Map<BindingKey<?>, Object> bindings) {
            bindings.forEach((key, value) -> {
                if (key instanceof ComponentKey<?> single) {
                    singles.put(single, value);
                } else {
                    multiples.computeIfAbsent((ComponentSetKey<?>) key, ignored -> new ArrayList<>()).add(value);
                }
            });
        }

        RuntimeBindings freeze() {
            return new RuntimeBindings(singles, multiples);
        }
    }
}
