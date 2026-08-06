package group.zn.zero.cache;

/**
 * 缓存 key 编码器。
 *
 * <p>该接口用于把业务缓存 key 转换为稳定、可版本化的规范化字符串。实现不得依赖
 * `Object#toString()` 的默认对象格式，否则跨进程和重启后无法保证同一业务 key 命中同一缓存。
 *
 * @param <K> 缓存 key 类型。
 * @author zn
 */
public interface CacheKeyCodec<K> {

    /**
     * 返回 codec 名称。
     *
     * @return codec 名称；不可为空；线程安全性由实现声明。
     */
    String name();

    /**
     * 返回 key 格式版本。
     *
     * @return key 格式版本；必须大于 0；线程安全性由实现声明。
     */
    int version();

    /**
     * 编码缓存 key。
     *
     * @param key 缓存 key；不可为空。
     * @return 规范化 key 字符串；不可为空；有序且稳定；线程安全性由实现声明。
     */
    String encode(K key);
}
