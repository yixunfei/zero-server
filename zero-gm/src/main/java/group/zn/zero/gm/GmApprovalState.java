package group.zn.zero.gm;

import java.util.Locale;

/**
 * GM 审计使用的受控审批状态。
 *
 * <p>本枚举只负责把现有上下文中的候选状态归一为安全、有限集合，
 * 不实现审批流、状态迁移或权限判定。</p>
 *
 * @author zn
 */
public enum GmApprovalState {

    /**
     * 当前指令不要求审批。
     */
    NOT_REQUIRED,

    /**
     * 指令要求审批，但上下文没有提供审批状态。
     */
    NOT_PROVIDED,

    /**
     * 审批仍在等待处理。
     */
    PENDING,

    /**
     * 审批已经通过。
     */
    APPROVED,

    /**
     * 审批已经拒绝。
     */
    REJECTED,

    /**
     * 审批已经过期。
     */
    EXPIRED,

    /**
     * 审批已经取消。
     */
    CANCELLED,

    /**
     * 输入状态不在当前受控集合中；原字符串不会进入审计事件。
     */
    UNKNOWN;

    /**
     * 将执行上下文中的候选审批状态归一为受控枚举。
     *
     * <p>本方法不修改上下文；未知输入只映射为 {@link #UNKNOWN}，不会进入异常或返回值。</p>
     *
     * @param approvalRequired 当前指令是否要求审批。
     * @param rawState 上下文中的原审批状态；允许为空。
     * @return 受控审批状态；不可为空；线程安全。
     */
    static GmApprovalState fromContext(final boolean approvalRequired, final String rawState) {
        if (!approvalRequired) {
            return NOT_REQUIRED;
        }
        if (rawState == null || rawState.isBlank()) {
            return NOT_PROVIDED;
        }
        String normalized = rawState.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "PENDING" -> PENDING;
            case "APPROVED" -> APPROVED;
            case "REJECTED" -> REJECTED;
            case "EXPIRED" -> EXPIRED;
            case "CANCELLED", "CANCELED" -> CANCELLED;
            default -> UNKNOWN;
        };
    }
}
