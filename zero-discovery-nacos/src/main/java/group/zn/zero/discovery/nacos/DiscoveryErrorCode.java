package group.zn.zero.discovery.nacos;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ErrorCode;

/**
 * 服务发现错误码。
 *
 * @author zn
 */
public enum DiscoveryErrorCode implements ErrorCode {

    /**
     * 注册表不可用。
     */
    REGISTRY_UNAVAILABLE(ErrorCategory.SYSTEM, "ZERO-DISCOVERY-REGISTRY-UNAVAILABLE", "service registry unavailable"),

    /**
     * 服务实例未找到。
     */
    INSTANCE_NOT_FOUND(ErrorCategory.SYSTEM, "ZERO-DISCOVERY-INSTANCE-NOT-FOUND", "service instance not found"),

    /**
     * 服务实例非法。
     */
    INVALID_INSTANCE(ErrorCategory.CLIENT_REQUEST, "ZERO-DISCOVERY-INVALID-INSTANCE", "invalid service instance"),

    /**
     * 服务发现配置非法。
     */
    DISCOVERY_CONFIGURATION_ERROR(
            ErrorCategory.CLIENT_REQUEST,
            "ZERO-DISCOVERY-CONFIGURATION-ERROR",
            "service discovery configuration error"),

    /**
     * 服务订阅失败。
     */
    SUBSCRIPTION_FAILED(ErrorCategory.SYSTEM, "ZERO-DISCOVERY-SUBSCRIPTION-FAILED", "service subscription failed"),

    /**
     * Nacos 客户端调用失败。
     */
    NACOS_CLIENT_ERROR(ErrorCategory.SYSTEM, "ZERO-DISCOVERY-NACOS-CLIENT-ERROR", "nacos client error"),

    /**
     * Nacos 配置非法。
     */
    NACOS_CONFIGURATION_ERROR(
            ErrorCategory.CLIENT_REQUEST,
            "ZERO-DISCOVERY-NACOS-CONFIGURATION-ERROR",
            "nacos configuration error"),

    /**
     * 监听器处理失败。
     */
    LISTENER_FAILED(ErrorCategory.SYSTEM, "ZERO-DISCOVERY-LISTENER-FAILED", "service discovery listener failed"),

    /**
     * 健康状态更新不支持。
     */
    HEALTH_UPDATE_UNSUPPORTED(
            ErrorCategory.SYSTEM,
            "ZERO-DISCOVERY-HEALTH-UPDATE-UNSUPPORTED",
            "health update unsupported");

    /**
     * 错误分类。
     */
    private final ErrorCategory category;

    /**
     * 错误码。
     */
    private final String code;

    /**
     * 默认说明。
     */
    private final String message;

    DiscoveryErrorCode(final ErrorCategory category, final String code, final String message) {
        this.category = category;
        this.code = code;
        this.message = message;
    }

    /**
     * 返回错误分类。
     *
     * @return 错误分类；不可为空；线程安全。
     */
    @Override
    public ErrorCategory category() {
        return category;
    }

    /**
     * 返回对外稳定错误码。
     *
     * @return 错误码；不可为空；线程安全。
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * 返回默认错误说明。
     *
     * @return 默认错误说明；不可为空；线程安全。
     */
    @Override
    public String message() {
        return message;
    }
}
