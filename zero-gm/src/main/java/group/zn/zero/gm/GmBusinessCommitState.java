package group.zn.zero.gm;

/**
 * GM 指令业务提交状态。
 *
 * <p>该状态描述调用方能否证明业务副作用已经提交，不能用裸 boolean 代替。
 * {@link #COMMITTED} 与 {@link #UNKNOWN} 都禁止自动重试，避免重复修改业务状态。</p>
 *
 * @author zn
 */
public enum GmBusinessCommitState {

    /**
     * 当前操作不涉及业务提交，例如 dry-run。
     */
    NOT_APPLICABLE,

    /**
     * 已确认业务 handler 未产生提交，例如 handler 调用前失败或无副作用拒绝。
     */
    NOT_COMMITTED,

    /**
     * 业务 handler 已正常成功返回，业务结果已经提交。
     */
    COMMITTED,

    /**
     * handler 已被调用但无法证明是否回滚，提交状态未知。
     */
    UNKNOWN
}
