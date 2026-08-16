package group.zn.zero.runtime.api;

/**
 * typed capability 的绑定基数。
 *
 * @author zn
 */
public enum BindingCardinality {

    /** 恰好一个显式实现。 */
    SINGLE,

    /** 一个或多个显式贡献者。 */
    MULTIPLE
}
