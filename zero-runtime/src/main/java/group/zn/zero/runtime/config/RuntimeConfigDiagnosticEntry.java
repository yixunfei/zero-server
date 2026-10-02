package group.zn.zero.runtime.config;

import group.zn.zero.runtime.api.ComponentId;
import java.util.Objects;
import java.util.Optional;

/**
 * 单个 typed 配置项的不可变诊断视图；敏感项只保留遮罩，非敏感项保留安全展示值。
 * @param owner 配置所属组件。
 * @param logicalKey 逻辑键名。
 * @param javaType 声明的 Java 类型名。
 * @param sourceKind 最终来源类别。
 * @param sourceId 来源稳定 ID；不得存放凭据。
 * @param sourceAlias 来源中的配置键名。
 * @param status 解析结果状态。
 * @param reloadability schema 声明的重载能力。
 * @param sensitive 是否敏感。
 * @param effectiveValue 安全展示值；缺省可选项为空，复杂类型为占位文本。
 * @author zn
 */
public record RuntimeConfigDiagnosticEntry(
        ComponentId owner,
        String logicalKey,
        String javaType,
        ConfigSourceKind sourceKind,
        String sourceId,
        String sourceAlias,
        ConfigValidationStatus status,
        ConfigReloadability reloadability,
        boolean sensitive,
        Optional<String> effectiveValue) {

    /**
     * 校验安全展示值；无外部副作用，构造后线程安全。
     * @throws NullPointerException 必填字段为空时抛出。
     * @throws IllegalArgumentException 敏感值未遮罩时抛出，不回显其内容。
     */
    public RuntimeConfigDiagnosticEntry {
        owner = Objects.requireNonNull(owner, "owner");
        logicalKey = Objects.requireNonNull(logicalKey, "logicalKey");
        javaType = Objects.requireNonNull(javaType, "javaType");
        sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        sourceId = Objects.requireNonNull(sourceId, "sourceId");
        sourceAlias = Objects.requireNonNull(sourceAlias, "sourceAlias");
        status = Objects.requireNonNull(status, "status");
        reloadability = Objects.requireNonNull(reloadability, "reloadability");
        effectiveValue = Objects.requireNonNull(effectiveValue, "effectiveValue");
        if (sensitive && effectiveValue.filter(value -> !value.equals("***")).isPresent()) {
            throw new IllegalArgumentException("sensitive diagnostic values must be masked");
        }
    }
}
