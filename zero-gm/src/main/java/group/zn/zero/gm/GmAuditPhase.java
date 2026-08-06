package group.zn.zero.gm;

/**
 * GM 审计阶段。
 *
 * @author zn
 */
public enum GmAuditPhase {

    /**
     * dry-run 预演开始前。
     */
    BEFORE_DRY_RUN,

    /**
     * dry-run 预演成功后。
     */
    AFTER_DRY_RUN,

    /**
     * dry-run 预演失败后。
     */
    DRY_RUN_FAILED,

    /**
     * 正式执行开始前。
     */
    BEFORE_EXECUTE,

    /**
     * 正式执行成功后。
     */
    AFTER_EXECUTE,

    /**
     * 正式执行失败后。
     */
    EXECUTE_FAILED
}
