package group.zn.zero.cache;

/**
 * 缓存值编码器。
 *
 * <p>该接口只处理 value payload，不处理 Redis envelope 或业务持久化 envelope。
 *
 * @param <V> 缓存值类型。
 * @author zn
 */
public interface CacheValueCodec<V> {

    /**
     * 返回 codec 名称。
     *
     * @return 名称；不可为空；线程安全。
     */
    String name();

    /**
     * 编码缓存值。
     *
     * @param value 缓存值；不可为空。
     * @return payload 字节；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 编码失败时抛出，必须绑定 ErrorCode。
     */
    byte[] encode(V value);

    /**
     * 解码缓存值。
     *
     * @param bytes payload 字节；不可为空。
     * @return 缓存值；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 解码失败时抛出，必须绑定 ErrorCode。
     */
    V decode(byte[] bytes);
}
