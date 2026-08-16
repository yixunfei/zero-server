package group.zn.zero.runtime.api;

import group.zn.zero.runtime.internal.RuntimeIdentifiers;

/**
 * runtime component/provider 的稳定、命名空间化身份。
 *
 * @param value 小写点号或连字符格式的稳定 ID。
 * @author zn
 */
public record ComponentId(String value) implements Comparable<ComponentId> {

    /**
     * 创建组件 ID。
     */
    public ComponentId {
        value = RuntimeIdentifiers.requireStableId(value, "componentId");
    }

    /**
     * 创建组件 ID。
     *
     * @param value 稳定 ID；不可为空。
     * @return 组件 ID；不可为空。
     */
    public static ComponentId of(final String value) {
        return new ComponentId(value);
    }

    /**
     * 按稳定 ID 排序。
     *
     * @param other 另一个 ID；不可为空。
     * @return 比较结果。
     */
    @Override
    public int compareTo(final ComponentId other) {
        return value.compareTo(other.value);
    }

    /**
     * 返回安全逻辑 ID。
     *
     * @return 稳定 ID；不可为空。
     */
    @Override
    public String toString() {
        return value;
    }
}
