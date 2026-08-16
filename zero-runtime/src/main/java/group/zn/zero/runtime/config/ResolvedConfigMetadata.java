package group.zn.zero.runtime.config;

import group.zn.zero.runtime.api.ComponentId;
import java.util.Objects;

/**
 * 不包含配置值的解析元数据。
 *
 * @param owner key owner。
 * @param logicalKey 稳定逻辑 key。
 * @param sourceKind 来源类别。
 * @param sourceId 安全来源 ID。
 * @param sourceAlias allowlist alias。
 * @param sensitive 是否敏感。
 * @param validationStatus 解析状态。
 * @param reloadability 变更语义。
 * @author zn
 */
public record ResolvedConfigMetadata(
        ComponentId owner,
        String logicalKey,
        ConfigSourceKind sourceKind,
        String sourceId,
        String sourceAlias,
        boolean sensitive,
        ConfigValidationStatus validationStatus,
        ConfigReloadability reloadability) {

    public ResolvedConfigMetadata {
        owner = Objects.requireNonNull(owner, "owner");
        logicalKey = Objects.requireNonNull(logicalKey, "logicalKey");
        sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        sourceId = Objects.requireNonNull(sourceId, "sourceId");
        sourceAlias = Objects.requireNonNull(sourceAlias, "sourceAlias");
        validationStatus = Objects.requireNonNull(validationStatus, "validationStatus");
        reloadability = Objects.requireNonNull(reloadability, "reloadability");
    }
}
