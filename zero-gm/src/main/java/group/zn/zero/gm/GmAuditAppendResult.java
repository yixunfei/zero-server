package group.zn.zero.gm;

/** Result of an append-only audit persistence attempt. */
public enum GmAuditAppendResult {
    ACCEPTED,
    DUPLICATE,
    REJECTED
}
