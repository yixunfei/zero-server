package group.zn.zero.statesync;

import java.util.Map;

/** Small in-memory baseline validator with explicit resync result. */
public final class InMemoryStateSync {
    private final Map<String, Long> baselines = new java.util.HashMap<>();
    public Result accept(final SyncEnvelope envelope) {
        Long known = baselines.get(envelope.observerId());
        if (envelope.kind() == SyncEnvelope.Kind.DELTA && (known == null || known != envelope.baselineVersion())) {
            return new Result(Status.RESYNC_REQUIRED, known == null ? -1 : known);
        }
        baselines.put(envelope.observerId(), envelope.stateVersion());
        return new Result(Status.APPLIED, envelope.stateVersion());
    }
    public enum Status { APPLIED, RESYNC_REQUIRED }
    public record Result(Status status, long baselineVersion) { }
}
