package group.zn.zero.statesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** 状态同步信封摘要校验回归。 @author zn */
class SyncEnvelopeTest {
    /** 显式摘要必须与规范化 payload 一致，防止伪造基线内容。 */
    @Test
    void explicitPayloadHashMustMatchPayload() {
        SyncEnvelope generated = new SyncEnvelope("scene", "observer", 1, 0, 1,
                SyncEnvelope.Kind.SNAPSHOT, Map.of("value", 1));

        assertEquals(generated.payloadHash(), new SyncEnvelope("scene", "observer", 1, 0, 1,
                SyncEnvelope.Kind.SNAPSHOT, Map.of("value", 1), generated.payloadHash()).payloadHash());
        assertEquals(generated.payloadHash(), new SyncEnvelope("scene", "observer", 1, 0, 1,
                SyncEnvelope.Kind.SNAPSHOT, Map.of("value", 1), generated.payloadHash().toUpperCase()).payloadHash());
        assertThrows(IllegalArgumentException.class, () -> new SyncEnvelope("scene", "observer", 1, 0, 1,
                SyncEnvelope.Kind.SNAPSHOT, Map.of("value", 1), "00"));
    }
}
