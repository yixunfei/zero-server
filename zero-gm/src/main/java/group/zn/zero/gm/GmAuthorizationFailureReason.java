package group.zn.zero.gm;

/** Stable, non-sensitive reason for rejecting a GM authorization request. */
public enum GmAuthorizationFailureReason {
    MISSING_OPERATOR,
    MISSING_SOURCE,
    MISSING_TARGET,
    ROLE_PERMISSION_DENIED,
    SOURCE_IP_DENIED,
    APPROVAL_REQUIRED,
    APPROVAL_TOKEN_INVALID,
    DUAL_REVIEW_REQUIRED
}
