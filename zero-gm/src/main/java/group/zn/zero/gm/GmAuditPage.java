package group.zn.zero.gm;

import java.util.List;
import java.util.Objects;

/** Stable bounded audit page. */
public record GmAuditPage(List<GmAuditRecord> records, String nextCursor, boolean hasMore) {
    public GmAuditPage {
        records = List.copyOf(Objects.requireNonNull(records, "records"));
        if (records.size() > 200) {
            throw new IllegalArgumentException("records must be bounded");
        }
        if (hasMore && (nextCursor == null || nextCursor.isBlank())) {
            throw new IllegalArgumentException("nextCursor required when more records exist");
        }
        if (!hasMore && nextCursor != null) {
            throw new IllegalArgumentException("nextCursor must be absent at end");
        }
    }
}
