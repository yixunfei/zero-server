package group.zn.zero.gm;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory idempotent record store used by focused tests and local fixtures. */
public final class InMemoryGmAuditRecordStore implements GmAuditRecordStore {
    private final Map<String, GmAuditRecord> records = new ConcurrentHashMap<>();

    /** Appends once per event ID and returns duplicate for a repeated ID. */
    @Override
    public GmAuditAppendResult append(final GmAuditRecord record) {
        Objects.requireNonNull(record, "record");
        return records.putIfAbsent(record.eventId(), record) == null
                ? GmAuditAppendResult.ACCEPTED
                : GmAuditAppendResult.DUPLICATE;
    }

    /** Returns the current number of unique records. */
    public int size() {
        return records.size();
    }
}
