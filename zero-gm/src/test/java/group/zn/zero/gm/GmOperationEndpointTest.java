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

    @Test
    void failedAuthorizationDoesNotClaimIdempotencyKey() {
        AtomicInteger authorizationAudits = new AtomicInteger();
        GmOperationEndpoint endpoint = new GmOperationEndpoint(
                GmCommandExecutor.systemClock(registry(new AtomicInteger()), event -> { }),
                new GmOperationAuthorizer(
                        new GmOperationAuthorizationPolicy(Set.of("admin"), Set.of(), Set.of(), false, false),
                        event -> authorizationAudits.incrementAndGet()),
                new InMemoryGmIdempotencyStore());
        GmOperationRequest request = new GmOperationRequest(
                GmCommandContext.simple("alice", "192.0.2.10", "trace-failed"),
                "/ping", "server", "", "", "failed-key", false);
        assertEquals(GmErrorCode.OPERATION_AUTHORIZATION_DENIED.code(), endpoint.handle(request).code());
        assertEquals(GmErrorCode.OPERATION_AUTHORIZATION_DENIED.code(), endpoint.handle(request).code());
        assertEquals(2, authorizationAudits.get());
    }

    /** 背压、冲突、执行中、历史失败各自稳定分类；均不再次调用业务。 */
    @Test void distinguishesClaimFailuresAndKeepsApprovalInFingerprint() {
        var request = new GmOperationRequest(GmCommandContext.simple("alice", "127.0.0.1", "trace"),
                "/ping", "server", "approved", "", "key", false);
        for (var entry : Map.of(GmIdempotencyStore.State.CAPACITY_REJECTED, GmErrorCode.IDEMPOTENCY_CAPACITY_EXCEEDED,
                GmIdempotencyStore.State.CONFLICT, GmErrorCode.IDEMPOTENCY_CONFLICT,
                GmIdempotencyStore.State.IN_PROGRESS, GmErrorCode.IDEMPOTENCY_IN_PROGRESS,
                GmIdempotencyStore.State.FAILED, GmErrorCode.IDEMPOTENCY_FAILED).entrySet()) {
            var store = new GmIdempotencyStore() {
                public Claim claim(String key, String fingerprint, java.time.Instant now, java.time.Duration ttl) {
                    return new Claim(entry.getKey(), null);
                }
                public void complete(String key, String fp, GmOperationResponse response, java.time.Instant expires) {
                    throw new AssertionError("must not complete");
                }
                public void fail(String key, String fp, java.time.Instant expires) { throw new AssertionError("must not execute"); }
            };
            var calls = new AtomicInteger();
            var endpoint = new GmOperationEndpoint(GmCommandExecutor.systemClock(registry(calls), event -> { }),
                    allowingAuthorizer(), store);
            var response = endpoint.handle(request);
            assertEquals(entry.getValue().code(), response.code());
            assertEquals(entry.getKey() == GmIdempotencyStore.State.IN_PROGRESS
                    || entry.getKey() == GmIdempotencyStore.State.FAILED
                    ? GmBusinessCommitState.UNKNOWN : GmBusinessCommitState.NOT_COMMITTED,
                    response.businessCommitState());
            org.junit.jupiter.api.Assertions.assertFalse(response.auditFailed());
            assertEquals(0, calls.get());
        }
        var changed = new GmOperationRequest(request.context(), request.rawCommand(), request.target(),
                "another-approval", "", request.idempotencyKey(), false);
        org.junit.jupiter.api.Assertions.assertNotEquals(GmOperationFingerprint.processLocal().fingerprint(request),
                GmOperationFingerprint.processLocal().fingerprint(changed));
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
        return new GmOperationAuthorizer(new GmOperationAuthorizationPolicy(Set.of(), Set.of(), Set.of("0.0.0.0/0", "::/0"), false, false),
                event -> { });
    }
}
