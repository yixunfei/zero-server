package group.zn.zero.discovery.nacos;

/**
 * Nacos 健康状态更新模式。
 *
 * @author zn
 */
public enum NacosHealthUpdateMode {

    /**
     * 更新本地登记状态，不重新写入 Nacos。
     */
    LOCAL_ONLY,

    /**
     * 通过重新注册实例写入健康状态。
     */
    REREGISTER,

    /**
     * 不支持健康状态更新，调用时快速失败。
     */
    FAIL_FAST
}
