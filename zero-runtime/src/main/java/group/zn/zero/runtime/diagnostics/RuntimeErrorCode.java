package group.zn.zero.runtime.diagnostics;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ErrorCode;

/**
 * 模块化运行时装配的稳定错误码。
 *
 * @author zn
 */
public enum RuntimeErrorCode implements ErrorCode {

    /** catalog 中出现重复 provider ID。 */
    RUNTIME_DUPLICATE_PROVIDER(ErrorCategory.CLIENT_REQUEST,
            "ZERO-RUNTIME-DUPLICATE-PROVIDER", "duplicate runtime provider"),

    /** 单值 binding 被多个激活 provider 提供。 */
    RUNTIME_DUPLICATE_BINDING(ErrorCategory.CLIENT_REQUEST,
            "ZERO-RUNTIME-DUPLICATE-BINDING", "duplicate runtime binding"),

    /** 配置逻辑 key 或 source alias 重复。 */
    RUNTIME_DUPLICATE_CONFIG_KEY(ErrorCategory.CLIENT_REQUEST,
            "ZERO-RUNTIME-DUPLICATE-CONFIG-KEY", "duplicate runtime config key"),

    /** selection 引用了 catalog 外 provider。 */
    RUNTIME_UNKNOWN_PROVIDER(ErrorCategory.CLIENT_REQUEST,
            "ZERO-RUNTIME-UNKNOWN-PROVIDER", "unknown runtime provider"),

    /** required capability 没有显式实现。 */
    RUNTIME_MISSING_CAPABILITY(ErrorCategory.CLIENT_REQUEST,
            "ZERO-RUNTIME-MISSING-CAPABILITY", "missing runtime capability"),

    /** 同一单值 capability 存在冲突选择。 */
    RUNTIME_AMBIGUOUS_CAPABILITY(ErrorCategory.CLIENT_REQUEST,
            "ZERO-RUNTIME-AMBIGUOUS-CAPABILITY", "ambiguous runtime capability"),

    /** 两个互斥组件同时被激活。 */
    RUNTIME_COMPONENT_CONFLICT(ErrorCategory.CLIENT_REQUEST,
            "ZERO-RUNTIME-COMPONENT-CONFLICT", "runtime component conflict"),

    /** 依赖或启动排序图存在循环。 */
    RUNTIME_DEPENDENCY_CYCLE(ErrorCategory.CLIENT_REQUEST,
            "ZERO-RUNTIME-DEPENDENCY-CYCLE", "runtime dependency cycle"),

    /** 必填配置不存在。 */
    RUNTIME_CONFIG_MISSING(ErrorCategory.CLIENT_REQUEST,
            "ZERO-RUNTIME-CONFIG-MISSING", "required runtime config is missing"),

    /** 配置解码、来源或校验失败。 */
    RUNTIME_CONFIG_INVALID(ErrorCategory.CLIENT_REQUEST,
            "ZERO-RUNTIME-CONFIG-INVALID", "runtime config is invalid"),

    /** profile policy 拒绝当前拓扑。 */
    RUNTIME_POLICY_REJECTED(ErrorCategory.CLIENT_REQUEST,
            "ZERO-RUNTIME-POLICY-REJECTED", "runtime profile policy rejected selection"),

    /** provider contribution 与 descriptor 不一致。 */
    RUNTIME_CONTRIBUTION_INVALID(ErrorCategory.SYSTEM,
            "ZERO-RUNTIME-CONTRIBUTION-INVALID", "runtime component contribution is invalid"),

    /** provider 创建失败。 */
    RUNTIME_COMPONENT_CREATE_FAILED(ErrorCategory.SYSTEM,
            "ZERO-RUNTIME-COMPONENT-CREATE-FAILED", "runtime component creation failed"),

    /** lifecycle 启动失败。 */
    RUNTIME_COMPONENT_START_FAILED(ErrorCategory.SYSTEM,
            "ZERO-RUNTIME-COMPONENT-START-FAILED", "runtime component start failed"),

    /** mandatory startup health 失败。 */
    RUNTIME_COMPONENT_HEALTH_FAILED(ErrorCategory.SYSTEM,
            "ZERO-RUNTIME-COMPONENT-HEALTH-FAILED", "runtime component health failed"),

    /** 累计启动预算耗尽。 */
    RUNTIME_STARTUP_TIMEOUT(ErrorCategory.SYSTEM,
            "ZERO-RUNTIME-STARTUP-TIMEOUT", "runtime startup timed out"),

    /** lifecycle 停止失败。 */
    RUNTIME_COMPONENT_STOP_FAILED(ErrorCategory.SYSTEM,
            "ZERO-RUNTIME-COMPONENT-STOP-FAILED", "runtime component stop failed"),

    /** build resource 关闭失败。 */
    RUNTIME_RESOURCE_CLOSE_FAILED(ErrorCategory.SYSTEM,
            "ZERO-RUNTIME-RESOURCE-CLOSE-FAILED", "runtime resource close failed"),

    /** single-use runtime 被再次启动。 */
    RUNTIME_REUSE_REJECTED(ErrorCategory.CLIENT_REQUEST,
            "ZERO-RUNTIME-REUSE-REJECTED", "runtime reuse is rejected");

    /** 错误分类。 */
    private final ErrorCategory category;

    /** 稳定外部错误码。 */
    private final String code;

    /** 固定安全说明。 */
    private final String message;

    RuntimeErrorCode(final ErrorCategory category, final String code, final String message) {
        this.category = category;
        this.code = code;
        this.message = message;
    }

    /**
     * 返回错误分类。
     *
     * @return 分类；不可为空。
     */
    @Override
    public ErrorCategory category() {
        return category;
    }

    /**
     * 返回稳定外部错误码。
     *
     * @return 错误码；不可为空。
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * 返回固定安全说明。
     *
     * @return 说明；不可为空。
     */
    @Override
    public String message() {
        return message;
    }
}
