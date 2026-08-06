package group.zn.zero.data.mapping;

/**
 * 数据键生成器。
 *
 * @param <K> 数据键类型。
 * @author zn
 */
public interface ZeroDataKeyGenerator<K> {

    /**
     * 生成数据键。
     *
     * @return 数据键；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 生成失败时抛出，必须绑定 ErrorCode。
     */
    K generate();
}
