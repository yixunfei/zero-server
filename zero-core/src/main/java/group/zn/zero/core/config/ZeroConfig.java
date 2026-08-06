package group.zn.zero.core.config;

import java.util.Map;
import java.util.Optional;

/**
 * 框架配置抽象。
 *
 * @author zn
 */
public interface ZeroConfig {

    /**
     * 判断配置键是否存在。
     *
     * @param key 配置键；不可为空。
     * @return true 表示存在；线程安全性由实现声明。
     */
    default boolean containsKey(final String key) {
        return get(key).isPresent();
    }

    /**
     * 根据配置键读取字符串值，找不到时返回默认值。
     *
     * @param key 配置键；不可为空。
     * @param defaultValue 默认值；可为空。
     * @return 配置值或默认值；线程安全性由实现声明。
     */
    default String getOrDefault(final String key, final String defaultValue) {
        return get(key).orElse(defaultValue);
    }

    /**
     * 强制读取配置值。
     *
     * @param key 配置键；不可为空。
     * @return 配置值；不可为空。
     * @throws group.zn.zero.core.error.ZeroException 当配置不存在时抛出。
     */
    default String require(final String key) {
        return get(key).orElseThrow(() -> group.zn.zero.core.error.ZeroException.of(
                group.zn.zero.core.error.SystemErrorCode.INVALID_ARGUMENT,
                "Missing required config: " + key,
                null));
    }

    /**
     * 根据配置键读取字符串值。
     *
     * @param key 配置键；不可为空。
     * @return 配置值；为空表示不存在；线程安全性由实现声明。
     */
    Optional<String> get(String key);

    /**
     * 返回当前配置快照。
     *
     * @return 配置快照；不可为空；是否可变、是否有序和线程安全性由实现声明。
     */
    Map<String, String> asMap();
}
