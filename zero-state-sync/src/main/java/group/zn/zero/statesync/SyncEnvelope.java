package group.zn.zero.statesync;

import java.util.Map;

/** Immutable synchronization envelope. */
public record SyncEnvelope(String sceneId, String observerId, long syncSeq, long baselineVersion,
                           long stateVersion, Kind kind, Map<String, Object> payload) {
    public SyncEnvelope {
        if (sceneId == null || observerId == null || kind == null || payload == null) throw new NullPointerException();
        payload = Map.copyOf(payload);
    }
    public enum Kind { SNAPSHOT, DELTA }
}
