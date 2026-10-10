package group.zn.zero.gm;

/** Database- and transport-neutral append-only store for safe GM records. */
@FunctionalInterface
public interface GmAuditRecordStore {
    /** Appends a record idempotently; duplicate event IDs must not create a second record. */
    GmAuditAppendResult append(GmAuditRecord record);
    /**
     * 核实相同 ID 的完整记录已持久化，作为 DUPLICATE 的成功证据；默认无保证。
     * @param record 完整安全记录。 @return 完全相等且已持久化才为 true。
     * @throws RuntimeException 存储不可用；实现保证线程安全，无记录变更。
     */
    default boolean containsExact(GmAuditRecord record) { return false; }
}
