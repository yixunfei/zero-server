package group.zn.zero.gm;

import java.util.Objects;

/** GmAuditHook adapter that persists only allow-listed audit records. */
public final class RecordGmAuditHook implements GmAuditHook {
    private final GmAuditRecordStore store;

    /** Creates an adapter around an application-owned store. */
    public RecordGmAuditHook(final GmAuditRecordStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Converts and appends an event; store failures are propagated. */
    @Override
    public void record(final GmAuditEvent event) {
        GmAuditAppendResult result = store.append(GmAuditRecord.from(Objects.requireNonNull(event, "event")));
        if (result == null) {
            throw new IllegalStateException("gm audit store returned null result");
        }
    }
}
