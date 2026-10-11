package group.zn.zero.runtime.production;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ErrorCode;

/**
 * Production Adapter 启动期错误码。
 *
 * @author zn
 */
public enum ProductionAdapterErrorCode implements ErrorCode {

    /** Adapter 选择器或配置值非法。 */
    CONFIG_INVALID(ErrorCategory.CLIENT_REQUEST,
            "ZERO-PRODUCTION-ADAPTER-CONFIG-INVALID", "production adapter config is invalid"),

    /** 已启用 Adapter 缺少必填配置。 */
    CONFIG_MISSING(ErrorCategory.CLIENT_REQUEST,
            "ZERO-PRODUCTION-ADAPTER-CONFIG-MISSING", "production adapter config is missing"),

    /** 显式执行器不能安全承载远程 IO。 */
    REMOTE_IO_EXECUTOR_REQUIRED(ErrorCategory.CLIENT_REQUEST,
            "ZERO-PRODUCTION-REMOTE-IO-EXECUTOR-REQUIRED",
            "use ProductionAssembly defaults or ZeroRuntimeExecutors.remoteIo; direct remote IO is unsupported"),

    /** 驱动或客户端创建失败。 */
    CLIENT_CREATION_FAILED(ErrorCategory.SYSTEM,
            "ZERO-PRODUCTION-ADAPTER-CLIENT-CREATION-FAILED", "production adapter client creation failed"),

    /** 外部服务连接失败。 */
    CONNECTION_FAILED(ErrorCategory.SYSTEM,
            "ZERO-PRODUCTION-ADAPTER-CONNECTION-FAILED", "production adapter connection failed"),

    /** 外部服务鉴权失败。 */
    AUTHENTICATION_FAILED(ErrorCategory.PERMISSION,
            "ZERO-PRODUCTION-ADAPTER-AUTHENTICATION-FAILED", "production adapter authentication failed"),

    /** Adapter 生命周期启动失败。 */
    STARTUP_FAILED(ErrorCategory.SYSTEM,
            "ZERO-PRODUCTION-ADAPTER-STARTUP-FAILED", "production adapter startup failed"),

    /** 服务注册失败。 */
    REGISTRATION_FAILED(ErrorCategory.SYSTEM,
            "ZERO-PRODUCTION-ADAPTER-REGISTRATION-FAILED", "production adapter registration failed"),

    /** 启动健康检查失败。 */
    STARTUP_HEALTH_FAILED(ErrorCategory.SYSTEM,
            "ZERO-PRODUCTION-ADAPTER-STARTUP-HEALTH-FAILED", "production adapter startup health failed"),

    /** 累计启动预算已耗尽。 */
    STARTUP_BUDGET_EXHAUSTED(ErrorCategory.SYSTEM,
            "ZERO-PRODUCTION-ADAPTER-STARTUP-BUDGET-EXHAUSTED", "production adapter startup budget exhausted"),

    /** 已关闭或已经尝试启动的 production runtime 被重复启动。 */
    RUNTIME_REUSE_REJECTED(ErrorCategory.CLIENT_REQUEST,
            "ZERO-PRODUCTION-RUNTIME-REUSE-REJECTED", "production runtime reuse is rejected"),

    /** 启动失败后的资源回滚失败。 */
    ROLLBACK_FAILED(ErrorCategory.SYSTEM,
            "ZERO-PRODUCTION-ADAPTER-ROLLBACK-FAILED", "production adapter rollback failed"),

    /** 资源关闭失败。 */
    CLOSE_FAILED(ErrorCategory.SYSTEM,
            "ZERO-PRODUCTION-ADAPTER-CLOSE-FAILED", "production adapter close failed"),

    /** 恢复期间或达到并发预算时拒绝远程请求。 */
    OPERATION_REJECTED(ErrorCategory.SYSTEM,
            "ZERO-PRODUCTION-ADAPTER-OPERATION-REJECTED", "production adapter operation rejected"),

    /** 运行期远程读取或确定未完成的操作失败。 */
    REMOTE_IO_FAILED(ErrorCategory.SYSTEM,
            "ZERO-PRODUCTION-ADAPTER-REMOTE-IO-FAILED", "production adapter remote IO failed"),

    /** 远程写入已发出，但无法确认是否提交。 */
    WRITE_OUTCOME_UNKNOWN(ErrorCategory.SYSTEM,
            "ZERO-PRODUCTION-ADAPTER-WRITE-OUTCOME-UNKNOWN", "production adapter write outcome unknown");

    /** 错误分类。 */
    private final ErrorCategory category;

    /** 稳定错误码。 */
    private final String code;

    /** 固定安全消息。 */
    private final String message;

    ProductionAdapterErrorCode(
            final ErrorCategory category,
            final String code,
            final String message) {
        this.category = category;
        this.code = code;
        this.message = message;
    }

    /**
     * 返回错误分类。
     *
     * @return 错误分类；不可为空，线程安全。
     */
    @Override
    public ErrorCategory category() {
        return category;
    }

    /**
     * 返回稳定错误码。
     *
     * @return 错误码；不可为空，线程安全。
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * 返回固定安全消息。
     *
     * @return 消息；不可为空，线程安全。
     */
    @Override
    public String message() {
        return message;
    }
}
