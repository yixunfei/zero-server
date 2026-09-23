package group.zn.zero.gm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryGmBreakGlassProviderTest {
    @Test
    void grantIsScopedAndOneShot() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        GmBreakGlassGrant grant = new GmBreakGlassGrant(
                "grant-1", "gm:emergency", "incident", "op-fingerprint",
                now.plusSeconds(60), true);
        InMemoryGmBreakGlassProvider provider = new InMemoryGmBreakGlassProvider();
        assertFalse(provider.verify(grant, "gm:other", "op-fingerprint", now).accepted());
        assertTrue(provider.verify(grant, "gm:emergency", "op-fingerprint", now).accepted());
        assertFalse(provider.verify(grant, "gm:emergency", "op-fingerprint", now).accepted());
    }

    @Test
    void expiredGrantFailsClosed() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        GmBreakGlassGrant grant = new GmBreakGlassGrant(
                "grant-2", "gm:emergency", "incident", "op-fingerprint",
                now, false);
        assertFalse(new InMemoryGmBreakGlassProvider()
                .verify(grant, "gm:emergency", "op-fingerprint", now).accepted());
    }
}
