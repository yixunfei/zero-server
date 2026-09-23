package group.zn.zero.gm;

/** Query and bounded page port for already-sanitized audit records. */
@FunctionalInterface
public interface GmAuditQueryStore {
    GmAuditPage query(GmAuditQuery query);
}
