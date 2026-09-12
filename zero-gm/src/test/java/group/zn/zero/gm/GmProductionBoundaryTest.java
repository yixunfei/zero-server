package group.zn.zero.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GmProductionBoundaryTest {
    @Test
    void recordStoreIsIdempotentAndDoesNotExposeRawValues() {
        InMemoryGmAuditRecordStore store = new InMemoryGmAuditRecordStore();
        GmAuditRecord record = new GmAuditRecord(
                "event-1", "operation-1", java.time.Instant.EPOCH, GmAuditPhase.BEFORE_EXECUTE,
                "trace-1", "mail send", 1, false, "STARTED", GmBusinessCommitState.NOT_COMMITTED,
                "", "safe", "operator-ref", "source-ref", "player", "target-ref", "approval-ref", "APPROVED", "structure");
        assertEquals(GmAuditAppendResult.ACCEPTED, store.append(record));
        assertEquals(GmAuditAppendResult.DUPLICATE, store.append(record));
        assertEquals(1, store.size());
        assertEquals(-1, record.toString().indexOf("raw-secret"));
    }

    @Test
    void transportMetadataFailsClosedAndMustMatchContext() {
        assertThrows(IllegalArgumentException.class, () -> new GmTransportMetadata(
                "trace", "correlation", "idem", "192.0.2.1", Map.of(), false));
        GmCommandContext context = new GmCommandContext("alice", "192.0.2.1", "trace",
                Set.of(), Set.of(), "", "", Map.of());
        GmTransportMetadata metadata = new GmTransportMetadata(
                "trace", "correlation", "idem", "192.0.2.1", Map.of(), true);
        assertEquals("trace", metadata.traceId());
        assertEquals("192.0.2.1", context.operatorIp());
    }
}
