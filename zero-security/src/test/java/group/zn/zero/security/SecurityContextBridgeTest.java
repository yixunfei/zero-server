package group.zn.zero.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecurityContextBridgeTest {
    private static final SecurityContext CONTEXT = new SecurityContext(
            "subject", Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:01:00Z"), "http", "peer",
            "source", "trace", "correlation", Set.of("read"), Map.of());

    @Test
    void scopedBindingRestoresPreviousContext() {
        assertTrue(SecurityContextBridge.current().isEmpty());
        String subject = SecurityContextBridge.with(CONTEXT,
                () -> SecurityContextBridge.current().orElseThrow().subject());
        assertEquals("subject", subject);
        assertTrue(SecurityContextBridge.current().isEmpty());
    }

    @Test
    void exceptionDoesNotLeakContext() {
        try {
            SecurityContextBridge.with(CONTEXT, () -> {
                throw new IllegalStateException("failure");
            });
        } catch (IllegalStateException expected) {
            assertTrue(SecurityContextBridge.current().isEmpty());
        }
    }
}
