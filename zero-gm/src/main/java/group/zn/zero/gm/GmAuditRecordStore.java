package group.zn.zero.gm;

/** Database- and transport-neutral append-only store for safe GM records. */
@FunctionalInterface
public interface GmAuditRecordStore {
    /** Appends a record idempotently; duplicate event IDs must not create a second record. */
    GmAuditAppendResult append(GmAuditRecord record);
}
