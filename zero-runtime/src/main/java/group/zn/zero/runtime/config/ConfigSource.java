package group.zn.zero.runtime.config;

import java.util.Optional;

/**
 * 显式、有序的原始配置来源。
 *
 * @author zn
 */
public interface ConfigSource {

    /**
     * 返回来源类别。
     *
     * @return 类别；不可为 DEFAULT。
     */
    ConfigSourceKind kind();

    /**
     * 返回安全、稳定的来源 ID。
     *
     * @return 来源 ID；不可包含路径或 URI。
     */
    String id();

    /**
     * 按 schema allowlist alias 查找原始值。
     *
     * @param alias 安全 alias；不可为空。
     * @return 原始值；为空表示不存在。
     */
    Optional<String> value(String alias);
}
