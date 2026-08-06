package group.zn.zero.data.mapping;

/**
 * 数据键编码器。
 *
 * @param <K> 数据键类型。
 * @author zn
 */
public interface ZeroDataKeyCodec<K> {

    /**
     * 编码数据键。
     *
     * @param key 数据键；不可为空。
     * @return 编码后的键；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 编码失败时抛出，必须绑定 ErrorCode。
     */
    String encode(K key);

    /**
     * 解码数据键。
     *
     * @param value 编码值；不可为空。
     * @return 数据键；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 解码失败时抛出，必须绑定 ErrorCode。
     */
    K decode(String value);
}
