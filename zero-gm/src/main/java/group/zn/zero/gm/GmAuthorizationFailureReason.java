package group.zn.zero.gm;

/** Stable, non-sensitive reason for rejecting a GM authorization request. */
public enum GmAuthorizationFailureReason {
    /** 未配置任何访问约束。 */
    POLICY_NOT_CONFIGURED,
    MISSING_OPERATOR,
    MISSING_SOURCE,
    MISSING_TARGET,
    /** 请求声明目标与实际命令目标不一致。 */
    TARGET_MISMATCH,
    ROLE_PERMISSION_DENIED,
    SOURCE_IP_DENIED,
    APPROVAL_REQUIRED,
    APPROVAL_TOKEN_INVALID,
    /** 来源、审批或复核服务失败，授权保持拒绝。 */
    VERIFICATION_UNAVAILABLE,
    DUAL_REVIEW_REQUIRED
}
