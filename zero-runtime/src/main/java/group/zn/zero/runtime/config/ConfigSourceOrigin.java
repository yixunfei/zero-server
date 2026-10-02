package group.zn.zero.runtime.config;

import group.zn.zero.runtime.internal.RuntimeIdentifiers;
import java.util.Objects;

/**
 * 单个来源值的可公开定位信息；不包含值或文件路径。
 * @param kind 实际来源类别。
 * @param id 安全来源 ID。
 * @param alias 实际命中的安全键名。
 * @author zn
 */
public record ConfigSourceOrigin(ConfigSourceKind kind, String id, String alias) {
    /** 校验定位信息，构造后线程安全；非法标识抛 IllegalArgumentException，空输入抛 NullPointerException。 */
    public ConfigSourceOrigin {
        Objects.requireNonNull(kind, "kind");
        id = RuntimeIdentifiers.requireStableId(id, "sourceId");
        alias = RuntimeIdentifiers.requireSafeAlias(alias, "sourceAlias");
    }
}
