package group.zn.zero.runtime.diagnostics;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.runtime.api.ComponentId;
import java.util.Objects;
import java.util.Optional;

/**
 * 不透传 provider 原始消息或 cause 的运行时装配异常。
 *
 * @author zn
 */
public final class RuntimeAssemblyException extends ZeroException {

    /** 失败阶段。 */
    private final RuntimeFailurePhase phase;

    /** 失败组件；规划级失败可为空。 */
    private final ComponentId componentId;

    /** 失败时可用的安全报告；规划失败可为空。 */
    private volatile RuntimeAssemblyReport report;

    /**
     * 创建安全异常。
     *
     * @param errorCode 稳定错误码；不可为空。
     * @param phase 失败阶段；不可为空。
     * @param componentId 失败组件；可为空。
     * @param message 只含稳定逻辑 ID 的安全消息；不可为空。
     */
    public RuntimeAssemblyException(
            final RuntimeErrorCode errorCode,
            final RuntimeFailurePhase phase,
            final ComponentId componentId,
            final String message) {
        super(Objects.requireNonNull(errorCode, "errorCode"), Objects.requireNonNull(message, "message"));
        this.phase = Objects.requireNonNull(phase, "phase");
        this.componentId = componentId;
    }

    /**
     * 使用固定错误说明和安全上下文创建异常。
     *
     * @param errorCode 稳定错误码；不可为空。
     * @param phase 失败阶段；不可为空。
     * @param componentId 失败组件；可为空。
     * @param safeContext 安全逻辑上下文；可为空，不得包含配置值。
     * @return 装配异常；不可为空。
     */
    public static RuntimeAssemblyException failure(
            final RuntimeErrorCode errorCode,
            final RuntimeFailurePhase phase,
            final ComponentId componentId,
            final String safeContext) {
        RuntimeErrorCode checkedCode = Objects.requireNonNull(errorCode, "errorCode");
        String message = checkedCode.message();
        if (safeContext != null && !safeContext.isBlank()) {
            message = message + " [" + safeContext + "]";
        }
        return new RuntimeAssemblyException(checkedCode, phase, componentId, message);
    }

    /**
     * 返回失败阶段。
     *
     * @return 阶段；不可为空。
     */
    public RuntimeFailurePhase phase() {
        return phase;
    }

    /**
     * 返回失败组件。
     *
     * @return 组件；为空表示规划级失败。
     */
    public Optional<ComponentId> componentId() {
        return Optional.ofNullable(componentId);
    }

    /**
     * 附加一次安全报告快照；不会覆盖已经存在的报告。
     *
     * @param assemblyReport 报告；不可为空。
     * @return 当前异常。
     */
    public synchronized RuntimeAssemblyException withReport(final RuntimeAssemblyReport assemblyReport) {
        if (report == null) {
            report = Objects.requireNonNull(assemblyReport, "assemblyReport");
        }
        return this;
    }

    /**
     * 返回失败时报告。
     *
     * @return 报告；规划级失败通常为空。
     */
    public Optional<RuntimeAssemblyReport> report() {
        return Optional.ofNullable(report);
    }
}
