package group.zn.zero.runtime.api;

import group.zn.zero.runtime.internal.RuntimeIdentifiers;
import java.util.Objects;

/**
 * 单值 typed capability key。
 *
 * @param id 稳定语义 ID。
 * @param type 运行时类型令牌。
 * @param <T> capability 对象类型。
 * @author zn
 */
public record ComponentKey<T>(String id, Class<T> type) implements BindingKey<T> {

    /**
     * 创建单值 key。
     */
    public ComponentKey {
        id = RuntimeIdentifiers.requireStableId(id, "componentKey");
        type = Objects.requireNonNull(type, "type");
    }

    /**
     * 创建单值 key。
     *
     * @param id 稳定语义 ID；不可为空。
     * @param type 类型令牌；不可为空。
     * @param <T> capability 类型。
     * @return 单值 key；不可为空。
     */
    public static <T> ComponentKey<T> single(final String id, final Class<T> type) {
        return new ComponentKey<>(id, type);
    }

    /**
     * 返回单值基数。
     *
     * @return {@link BindingCardinality#SINGLE}。
     */
    @Override
    public BindingCardinality cardinality() {
        return BindingCardinality.SINGLE;
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
