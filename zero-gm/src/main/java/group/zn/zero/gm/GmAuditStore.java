package group.zn.zero.gm;

/** Persistence port for safe GM audit events; implementations own retention and durability. */
@FunctionalInterface
public interface GmAuditStore {
    /** Persists one already-redacted event; failures must be propagated. */
    void append(GmAuditEvent event);
}
