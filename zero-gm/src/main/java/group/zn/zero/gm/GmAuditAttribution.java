package group.zn.zero.gm;

import java.util.Objects;

/**
 * 已在事件构造前完成安全转换的 GM 审计归因。
 *
 * <p>本类型不保存原 operator、来源地址、approvalId 或目标值。实例只能由
 * {@link GmAuditAttributionFactory} 创建，从类型边界阻止调用方把原始上下文直接放入审计事件。</p>
 *
 * @author zn
 */
public final class GmAuditAttribution {

    /** 操作者安全引用。 */
    private final String operatorRef;

    /** 来源地址安全引用。 */
    private final String sourceAddressRef;

    /** 当前指令是否要求审批。 */
    private final boolean approvalRequired;

    /** 受控审批状态。 */
    private final GmApprovalState approvalState;

    /** 审批单安全引用；未提供审批单时为空字符串。 */
    private final String approvalRef;

    /** 目标类型；没有目标参数时为 {@code none}。 */
    private final String targetType;

    /** 目标安全引用；没有目标值时为空字符串。 */
    private final String targetRef;

    private GmAuditAttribution(
            final String operatorRef,
            final String sourceAddressRef,
            final boolean approvalRequired,
            final GmApprovalState approvalState,
            final String approvalRef,
            final String targetType,
            final String targetRef) {
        this.operatorRef = Objects.requireNonNull(operatorRef, "operatorRef");
        this.sourceAddressRef = Objects.requireNonNull(sourceAddressRef, "sourceAddressRef");
        this.approvalRequired = approvalRequired;
        this.approvalState = Objects.requireNonNull(approvalState, "approvalState");
        this.approvalRef = Objects.requireNonNull(approvalRef, "approvalRef");
        this.targetType = Objects.requireNonNull(targetType, "targetType");
        this.targetRef = Objects.requireNonNull(targetRef, "targetRef");
    }

    /**
     * 由同包安全工厂创建归因值对象。
     *
     * @param operatorRef 操作者安全引用；不可为空。
     * @param sourceAddressRef 来源地址安全引用；不可为空。
     * @param approvalRequired 当前指令是否要求审批。
     * @param approvalState 受控审批状态；不可为空。
     * @param approvalRef 审批单安全引用；不可为空，可为空字符串。
     * @param targetType 目标类型；不可为空。
     * @param targetRef 目标安全引用；不可为空，可为空字符串。
     * @return 不可变安全归因；不可为空；线程安全。
     * @throws NullPointerException 当任一引用、状态或目标类型为空时抛出。
     */
    static GmAuditAttribution safeReferences(
            final String operatorRef,
            final String sourceAddressRef,
            final boolean approvalRequired,
            final GmApprovalState approvalState,
            final String approvalRef,
            final String targetType,
            final String targetRef) {
        return new GmAuditAttribution(
                operatorRef,
                sourceAddressRef,
                approvalRequired,
                approvalState,
                approvalRef,
                targetType,
                targetRef);
    }

    /**
     * 返回操作者安全引用。
     *
     * @return 安全引用；不可为空；不可变；线程安全。
     */
    public String operatorRef() {
        return operatorRef;
    }

    /**
     * 返回来源地址安全引用。
     *
     * @return 安全引用；不可为空；不可变；线程安全。
     */
    public String sourceAddressRef() {
        return sourceAddressRef;
    }

    /**
     * 返回当前指令是否要求审批。
     *
     * @return 要求审批时为 true；线程安全。
     */
    public boolean approvalRequired() {
        return approvalRequired;
    }

    /**
     * 返回受控审批状态。
     *
     * @return 审批状态；不可为空；线程安全。
     */
    public GmApprovalState approvalState() {
        return approvalState;
    }

    /**
     * 返回审批单安全引用。
     *
     * @return 安全引用；不可为空；未提供审批单时为空字符串；线程安全。
     */
    public String approvalRef() {
        return approvalRef;
    }

    /**
     * 返回目标类型。
     *
     * @return 目标类型；不可为空；线程安全。
     */
    public String targetType() {
        return targetType;
    }

    /**
     * 返回目标安全引用。
     *
     * @return 安全引用；不可为空；没有目标值时为空字符串；线程安全。
     */
    public String targetRef() {
        return targetRef;
    }

    /**
     * 返回不包含任何安全引用值的诊断字符串。
     *
     * @return 有界诊断文本；不可为空；不会包含原始身份、来源、审批单、目标或密钥；线程安全。
     */
    @Override
    public String toString() {
        return "GmAuditAttribution{approvalRequired=" + approvalRequired
                + ", approvalState=" + approvalState
                + ", targetType='" + targetType + "'}";
    }
}
