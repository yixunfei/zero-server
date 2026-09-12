package group.zn.zero.gm;

import java.util.Objects;

/** Audit hook adapter that delegates persistence to an application-owned secure store. */
public final class PersistentGmAuditHook implements GmAuditHook {
    private final GmAuditStore store;

    /** Creates a hook around an explicitly configured audit store. */
    public PersistentGmAuditHook(final GmAuditStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Appends the already-redacted event and propagates store failures. */
    @Override
    public void record(final GmAuditEvent event) {
        store.append(Objects.requireNonNull(event, "event"));
    }
}
