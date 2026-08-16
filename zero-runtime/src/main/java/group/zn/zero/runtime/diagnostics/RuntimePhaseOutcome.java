package group.zn.zero.runtime.diagnostics;

/**
 * 报告中的单阶段结果。
 *
 * @author zn
 */
public enum RuntimePhaseOutcome {

    /** 尚未执行。 */
    NOT_RUN,

    /** 当前组件不适用该阶段。 */
    NOT_APPLICABLE,

    /** 成功。 */
    SUCCEEDED,

    /** 失败。 */
    FAILED
}
