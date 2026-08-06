package group.zn.zero.data.mapping;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;

/**
 * 禁用的数据键生成器。
 *
 * @author zn
 */
public final class NoopZeroDataKeyGenerator implements ZeroDataKeyGenerator<Object> {

    /**
     * 创建禁用的数据键生成器。
     */
    public NoopZeroDataKeyGenerator() {
    }

    /**
     * 生成数据键。
     *
     * @return 不返回；线程安全。
     * @throws ZeroException 始终抛出，表示未配置生成器。
     */
    @Override
    public Object generate() {
        throw ZeroException.of(DataErrorCode.MAPPING_INVALID, "data key generator is not configured", null);
    }
}
