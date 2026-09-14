package group.zn.zero.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GmOperationEndpointTest {
    @Test
    void convertsAuthorizedExecutionToStableResponse() {
        GmCommandRegistry registry = registry(new AtomicInteger());
        InMemoryGmAuditHook audit = new InMemoryGmAuditHook();
        GmOperationEndpoint endpoint = new GmOperationEndpoint(
                GmCommandExecutor.systemClock(registry, audit),
                allowingAuthorizer());
        GmOperationResponse response = endpoint.handle(new GmOperationRequest(
                GmCommandContext.simple("alice", "192.0.2.10", "trace-1"), "/ping", "server", "", "", false));
        assertEquals("ZERO-OK", response.code());
        assertEquals("trace-1", response.traceId());
    }

    @Test
    void repeatsCompletedOperationWithoutCallingHandlerAgain() {
        AtomicInteger calls = new AtomicInteger();
        GmCommandRegistry registry = registry(calls);
        GmOperationEndpoint endpoint = new GmOperationEndpoint(
                GmCommandExecutor.systemClock(registry, event -> { }),
                allowingAuthorizer(), new InMemoryGmIdempotencyStore());
        GmCommandContext context = GmCommandContext.simple("alice", "192.0.2.10", "trace-idempotent");
        GmOperationRequest request = new GmOperationRequest(context, "/ping", "server", "", "", "key-1", false);
        assertEquals("ZERO-OK", endpoint.handle(request).code());
        assertEquals("ZERO-OK", endpoint.handle(request).code());
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsBeforeHandlerAndKeepsTrace() {
        AtomicInteger calls = new AtomicInteger();
        GmOperationEndpoint endpoint = new GmOperationEndpoint(
                GmCommandExecutor.systemClock(registry(calls), event -> { }),
                new GmOperationAuthorizer(new GmOperationAuthorizationPolicy(Set.of("admin"), Set.of(), Set.of(), false, false),
                        event -> { }));
        GmOperationResponse response = endpoint.handle(new GmOperationRequest(
                GmCommandContext.simple("alice", "192.0.2.10", "trace-2"), "/ping", "server", "", "", false));
        assertEquals(GmErrorCode.OPERATION_AUTHORIZATION_DENIED.code(), response.code());
        assertEquals("trace-2", response.traceId());
        assertTrue(calls.get() == 0);
    }

    private static GmCommandRegistry registry(final AtomicInteger calls) {
        GmCommandRegistry registry = new GmCommandRegistry();
        registry.register(new GmCommandDefinition(List.of("ping"), List.of(), "ping", GmCommandRisk.LOW, "", false),
                new GmCommandHandler() {
                    @Override
                    public GmDryRunResult dryRun(final GmCommandExecutionRequest request) {
                        return GmDryRunResult.accepted("ping", "preview", Map.of());
                    }

                    @Override
                    public GmCommandExecutionResult execute(final GmCommandExecutionRequest request) {
                        calls.incrementAndGet();
                        return GmCommandExecutionResult.success("ping", "ok", Map.of());
                    }
                });
        return registry;
    }

    private static GmOperationAuthorizer allowingAuthorizer() {
        return new GmOperationAuthorizer(new GmOperationAuthorizationPolicy(Set.of(), Set.of(), Set.of(), false, false),
                event -> { });
    }
}
