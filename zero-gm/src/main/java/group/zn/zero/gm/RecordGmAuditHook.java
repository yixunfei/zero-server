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
        Objects.requireNonNull(event, "event");
        try {
            GmAuditRecord record = GmAuditRecord.from(event);
            GmAuditAppendResult result = store.append(record);
            if (result != GmAuditAppendResult.ACCEPTED
                    && !(result == GmAuditAppendResult.DUPLICATE && store.containsExact(record))) {
                throw new IllegalStateException("gm audit append was not accepted: " + result);
            }
        } catch (RuntimeException failure) {
            throw new GmAuditFailureException(event.phase(), event.businessCommitState(), failure);
        }
    }
}
