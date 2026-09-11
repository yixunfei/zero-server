package group.zn.zero.runtime.production;

/**
 * Production Adapter 启动失败阶段。
 *
 * <p>阶段值仅描述框架能够可靠归因的边界，不根据第三方异常文本猜测连接地址、账号或认证详情。
 *
 * @author zn
 */
public enum ProductionAdapterFailurePhase {

    /** 当前状态没有失败。 */
    NONE,

    /** Adapter 选择器解析。 */
    CONFIG_SELECTION,

    /** Adapter 必填配置校验。 */
    CONFIG_VALIDATION,

    /** 驱动或客户端创建。 */
    CLIENT_CREATION,

    /** 外部服务连接。 */
    CONNECT,

    /** 外部服务鉴权。 */
    AUTHENTICATION,

    /** Adapter 生命周期启动。 */
    STARTUP,

    /** 服务注册。 */
    REGISTRATION,

    /** 启动健康检查。 */
    STARTUP_HEALTH,

    /** 累计启动预算检查。 */
    STARTUP_BUDGET,

    /** 启动失败后的资源回滚。 */
    ROLLBACK,

    /** 正常或补偿关闭。 */
    CLOSE
}
