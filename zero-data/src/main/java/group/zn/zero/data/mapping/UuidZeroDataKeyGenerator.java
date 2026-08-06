package group.zn.zero.data.mapping;

import java.util.UUID;

/**
 * UUID 字符串数据键生成器。
 *
 * @author zn
 */
public final class UuidZeroDataKeyGenerator implements ZeroDataKeyGenerator<String> {

    /**
     * 创建 UUID 字符串数据键生成器。
     */
    public UuidZeroDataKeyGenerator() {
    }

    /**
     * 生成 UUID 字符串。
     *
     * @return UUID 字符串；不可为空；线程安全。
     */
    @Override
    public String generate() {
        return UUID.randomUUID().toString();
    }
}
