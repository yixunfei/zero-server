package group.zn.zero.gm;

/**
 * GM 指令风险等级。
 *
 * @author zn
 */
public enum GmCommandRisk {

    /**
     * 只读查询或低风险预演。
     */
    LOW,

    /**
     * 可能改变玩家、场景或运营数据的普通操作。
     */
    MEDIUM,

    /**
     * 可能影响经济系统、封禁、热更或跨服状态的高风险操作。
     */
    HIGH,

    /**
     * 必须额外审批或人工复核的危险操作。
     */
    CRITICAL;

    /** @return HIGH/CRITICAL 必须审批；无数据变更，线程安全。 */
    public boolean requiresApproval() { return this == HIGH || this == CRITICAL; }

    /** @return CRITICAL 必须可信第二人复核；无数据变更，线程安全。 */
    public boolean requiresDualReview() { return this == CRITICAL; }
}
