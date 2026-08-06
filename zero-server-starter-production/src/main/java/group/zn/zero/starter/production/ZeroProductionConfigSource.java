package group.zn.zero.starter.production;

import java.util.Objects;

/**
 * 生产配置来源诊断项。
 *
 * <p>该记录只保存逻辑配置键、来源类型和来源键名，不保存原始配置值。
 *
 * @param logicalKey 逻辑配置键。
 * @param sourceType 来源类型，例如 `ZeroConfig`、`systemProperty` 或 `environment`。
 * @param sourceKey 实际命中的键名。
 * @param sensitive 是否属于敏感配置项。
 * @author zn
 */
public record ZeroProductionConfigSource(
        String logicalKey,
        String sourceType,
        String sourceKey,
        boolean sensitive) {

    /**
     * 创建配置来源诊断项。
     *
     * @throws NullPointerException 当必填字段为空时抛出。
     * @throws IllegalArgumentException 当文本字段为空白时抛出。
     */
    public ZeroProductionConfigSource {
        logicalKey = requireText(logicalKey, "logicalKey");
        sourceType = requireText(sourceType, "sourceType");
        sourceKey = requireText(sourceKey, "sourceKey");
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
