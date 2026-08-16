package group.zn.zero.runtime.api;

import group.zn.zero.runtime.internal.RuntimeIdentifiers;
import java.util.Objects;

/**
 * 多值 typed capability key。
 *
 * @param id 稳定语义 ID。
 * @param type 单个贡献对象的运行时类型令牌。
 * @param <T> capability 对象类型。
 * @author zn
 */
public record ComponentSetKey<T>(String id, Class<T> type) implements BindingKey<T> {

    /**
     * 创建多值 key。
     */
    public ComponentSetKey {
        id = RuntimeIdentifiers.requireStableId(id, "componentSetKey");
        type = Objects.requireNonNull(type, "type");
    }

    /**
     * 创建多值 key。
     *
     * @param id 稳定语义 ID；不可为空。
     * @param type 单个贡献对象类型；不可为空。
     * @param <T> capability 类型。
     * @return 多值 key；不可为空。
     */
    public static <T> ComponentSetKey<T> multiple(final String id, final Class<T> type) {
        return new ComponentSetKey<>(id, type);
    }

    /**
     * 返回多值基数。
     *
     * @return {@link BindingCardinality#MULTIPLE}。
     */
    @Override
    public BindingCardinality cardinality() {
        return BindingCardinality.MULTIPLE;
    }

    /**
     * 仅打印安全逻辑 ID。
     *
     * @return key ID；不可为空。
     */
    @Override
    public String toString() {
        return id;
    }
}
