package group.zn.zero.gm;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Deterministic local query view over sanitized audit records. */
public final class InMemoryGmAuditQueryStore implements GmAuditQueryStore {
    private final List<GmAuditRecord> records;

    public InMemoryGmAuditQueryStore(final List<GmAuditRecord> records) {
        this.records = List.copyOf(Objects.requireNonNull(records, "records"));
    }

    @Override
    public GmAuditPage query(final GmAuditQuery query) {
        GmAuditQuery current = Objects.requireNonNull(query, "query");
        List<GmAuditRecord> filtered = records.stream()
                .filter(record -> !record.time().isBefore(current.from())
                        && !record.time().isAfter(current.to()))
                .filter(record -> current.commandKey() == null
                        || record.commandKey().equals(current.commandKey()))
                .sorted(Comparator.comparing(GmAuditRecord::time)
                        .thenComparing(GmAuditRecord::eventId))
                .toList();
        int start = cursorIndex(filtered, current.cursor());
        int end = Math.min(filtered.size(), start + current.limit());
        List<GmAuditRecord> page = new ArrayList<>(filtered.subList(start, end));
        boolean more = end < filtered.size();
        String next = more ? page.get(page.size() - 1).eventId() : null;
        return new GmAuditPage(page, next, more);
    }

    private static int cursorIndex(final List<GmAuditRecord> records, final String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        for (int index = 0; index < records.size(); index++) {
            if (records.get(index).eventId().equals(cursor)) {
                return index + 1;
            }
        }
        throw new IllegalArgumentException("audit cursor is invalid");
    }
}
