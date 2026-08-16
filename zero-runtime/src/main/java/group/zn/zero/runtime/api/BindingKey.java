package group.zn.zero.runtime.api;

/**
 * runtime typed capability key。
 *
 * @param <T> capability 对象类型。
 * @author zn
 */
public sealed interface BindingKey<T> permits ComponentKey, ComponentSetKey {

    /**
     * 返回稳定语义 ID。
     *
     * @return capability ID；不可为空。
     */
    String id();

    /**
     * 返回运行时类型令牌。
     *
     * @return 对象类型；不可为空。
     */
    Class<T> type();

    /**
     * 返回绑定基数。
     *
     * @return 基数；不可为空。
     */
    BindingCardinality cardinality();
}
