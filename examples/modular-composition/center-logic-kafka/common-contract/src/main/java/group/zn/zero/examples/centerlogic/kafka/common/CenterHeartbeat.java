package group.zn.zero.examples.centerlogic.kafka.common;

/** Heartbeat metadata shared by center and logic processes. */
public record CenterHeartbeat(String instanceId, String traceId, long observedAtEpochMillis) {
    public CenterHeartbeat {
        if (instanceId == null || instanceId.isBlank() || traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("heartbeat fields must not be blank");
        }
    }
}
