package group.zn.zero.runtime.health;

/**
 * 组件健康检查阶段。
 *
 * @author zn
 */
public enum HealthPhase {

    /** 启动窗口内的最小可用性检查。 */
    STARTUP,

    /** 是否应接收新流量。 */
    READINESS,

    /** 进程是否仍具备自行恢复能力。 */
    LIVENESS
}
