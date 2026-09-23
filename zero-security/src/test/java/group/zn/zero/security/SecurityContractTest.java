package group.zn.zero.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecurityContractTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test void failClosedProvidersRejectByDefault() throws Exception {
        assertFalse(AuthenticationProvider.failClosed().authenticate(request()).toCompletableFuture().join().accepted());
        assertEquals(ReplayProtection.ReplayDecision.REJECTED,
                ReplayProtection.failClosed().check(new ReplayProtection.ReplayRequest("n", NOW, 0, "c", java.time.Duration.ofSeconds(1))).toCompletableFuture().join());
    }
    @Test void contextRejectsSensitiveAttributesAndExpiredIsDeterministic() {
        assertThrows(IllegalArgumentException.class, () -> context(Map.of("password", "x")));
        assertTrue(context(Map.of("region", "test")).expired(NOW.plusSeconds(60)));
    }
    @Test
    void trustedProxyResolverRejectsUnboundedOrInvalidForwarding() {
        TrustedProxyResolver resolver = new TrustedProxyResolver(
                List.of(address -> address.equals("192.0.2.10")), 2);
        assertEquals("192.0.2.11", resolver.resolve("192.0.2.10", "192.0.2.11").sourceAddress());
        assertEquals("192.0.2.12", resolver.resolve("192.0.2.12", "192.0.2.11").sourceAddress());
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("192.0.2.10", "192.0.2.11,192.0.2.12,192.0.2.13"));
    }

    @Test
    void requestAndTlsBoundariesRejectInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> new AuthenticationProvider.AuthenticationRequest("", "tcp", "peer", "trace", NOW));
        assertThrows(IllegalArgumentException.class, () -> new TlsMaterialProvider.TlsMaterialSnapshot("v", NOW, NOW, "private-key-secret"));
        assertThrows(IllegalArgumentException.class, () -> new SecurityChain(AuthenticationProvider.failClosed(), ReplayProtection.failClosed(), null, true));
        assertThrows(NullPointerException.class, () -> SecurityChain.production(null, ReplayProtection.failClosed(), () -> material()));
    }

    private static TlsMaterialProvider.TlsMaterialSnapshot material() {
        return new TlsMaterialProvider.TlsMaterialSnapshot("v1", NOW, NOW.plusSeconds(60), "keystore-ref");
    }

    private static AuthenticationProvider.AuthenticationRequest request() {
        return new AuthenticationProvider.AuthenticationRequest("credential-ref", "tcp", "peer", "trace", NOW);
    }
    private static SecurityContext context(Map<String,String> attrs) {
        return new SecurityContext("subject", NOW, NOW.plusSeconds(1), "tcp", "peer", "source", "trace", "corr", Set.of("read"), attrs);
    }
}
