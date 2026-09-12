package group.zn.zero.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GmOperationEndpointTest {
    @Test
    void convertsAuthorizedExecutionToStableResponse() {
        GmCommandRegistry registry = new GmCommandRegistry();
        registry.register(new GmCommandDefinition(List.of("ping"), List.of(), "ping", GmCommandRisk.LOW, "", false),
                new GmCommandHandler() {
                    @Override
                    public GmDryRunResult dryRun(final GmCommandExecutionRequest request) {
                        return GmDryRunResult.accepted("ping", "preview", Map.of());
                    }

                    @Override
                    public GmCommandExecutionResult execute(final GmCommandExecutionRequest request) {
                        return GmCommandExecutionResult.success("ping", "ok", Map.of());
                    }
                });
        InMemoryGmAuditHook audit = new InMemoryGmAuditHook();
        GmOperationEndpoint endpoint = new GmOperationEndpoint(
                GmCommandExecutor.systemClock(registry, audit),
                new GmOperationAuthorizer(new GmOperationAuthorizationPolicy(Set.of(), Set.of(), Set.of(), false, false),
                        event -> { }));
        GmOperationResponse response = endpoint.handle(new GmOperationRequest(
                GmCommandContext.simple("alice", "192.0.2.10", "trace-1"), "/ping", "server", "", "", false));
        assertEquals("ZERO-OK", response.code());
        assertEquals("trace-1", response.traceId());
    }

    @Test
    void rejectsBeforeHandlerAndKeepsTrace() {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        GmCommandRegistry registry = new GmCommandRegistry();
        registry.register(new GmCommandDefinition(List.of("ping"), List.of(), "ping", GmCommandRisk.LOW, "", false),
                new GmCommandHandler() {
                    @Override
                    public GmDryRunResult dryRun(final GmCommandExecutionRequest request) {
                        return GmDryRunResult.accepted("ping", "preview", Map.of());
                    }

                    @Override
                    public GmCommandExecutionResult execute(final GmCommandExecutionRequest request) {
                        called.set(true);
                        return GmCommandExecutionResult.success("ping", "ok", Map.of());
                    }
                });
        GmOperationEndpoint endpoint = new GmOperationEndpoint(
                GmCommandExecutor.systemClock(registry, event -> { }),
                new GmOperationAuthorizer(new GmOperationAuthorizationPolicy(Set.of("admin"), Set.of(), Set.of(), false, false),
                        event -> { }));
        GmOperationResponse response = endpoint.handle(new GmOperationRequest(
                GmCommandContext.simple("alice", "192.0.2.10", "trace-2"), "/ping", "server", "", "", false));
        assertEquals(GmErrorCode.OPERATION_AUTHORIZATION_DENIED.code(), response.code());
        assertEquals("trace-2", response.traceId());
        assertTrue(!called.get());
    }
}
