package group.zn.zero.runtime.config;

/**
 * 把单个原始字符串解码为 typed startup 配置。
 *
 * @param <T> 目标类型。
 * @author zn
 */
@FunctionalInterface
public interface ConfigDecoder<T> {

    /**
     * 解码配置。
     *
     * @param raw 原始值；不可为空。
     * @return typed 值；不可为空。
     * @throws Exception 解码失败时抛出；装配边界不会透传原始消息。
     */
    T decode(String raw) throws Exception;
}
