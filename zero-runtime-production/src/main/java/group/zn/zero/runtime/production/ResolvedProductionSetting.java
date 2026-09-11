package group.zn.zero.runtime.production;

import java.util.Objects;
import java.util.Optional;

/**
 * 已解析的生产配置项。
 *
 * <p>该记录可在装配内部携带原始值；对外诊断只能暴露 `source()`，不得暴露 `value()`。
 *
 * @param logicalKey 逻辑配置键。
 * @param value 配置值；为空表示未命中。
 * @param source 配置来源；为空表示未命中。
 * @author zn
 */
public record ResolvedProductionSetting(
        String logicalKey,
        Optional<String> value,
        Optional<ZeroProductionConfigSource> source) {

    /**
     * 创建已解析配置项。
     *
     * @throws NullPointerException 当字段为空时抛出。
     */
    public ResolvedProductionSetting {
        logicalKey = Objects.requireNonNull(logicalKey, "logicalKey");
        value = Objects.requireNonNull(value, "value");
        source = Objects.requireNonNull(source, "source");
    }

    /**
     * 返回是否命中有效配置。
     *
     * @return true 表示命中配置；线程安全。
     */
    public boolean present() {
        return value.isPresent();
    }

    /**
     * 返回配置值或默认值。
     *
     * @param defaultValue 默认值；可为空。
     * @return 配置值或默认值。
     */
    public String orElse(final String defaultValue) {
        return value.orElse(defaultValue);
    }

    /**
     * 返回必填配置值。
     *
     * @return 配置值；不可为空。
     * @throws java.util.NoSuchElementException 当配置未命中时抛出。
     */
    public String require() {
        return value.orElseThrow();
    }

    /**
     * 返回不包含配置值的安全摘要。
     *
     * @return 仅含逻辑键、命中状态和来源的安全摘要；不可为空，线程安全。
     */
    @Override
    public String toString() {
        return "ResolvedProductionSetting{"
                + "logicalKey='" + logicalKey + '\''
                + ", present=" + value.isPresent()
                + ", source=" + source.orElse(null)
                + '}';
    }
}
