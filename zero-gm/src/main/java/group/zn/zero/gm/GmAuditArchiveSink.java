package group.zn.zero.gm;

/** Explicit cold archive boundary; implementations own durability and encryption. */
@FunctionalInterface
public interface GmAuditArchiveSink {
    void archive(GmAuditRecord record);
}
