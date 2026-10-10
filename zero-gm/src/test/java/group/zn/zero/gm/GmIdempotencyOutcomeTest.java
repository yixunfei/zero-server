package group.zn.zero.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 完成期保留、未知记账结果和原始 Error 的确定性回归。 @author zn */
class GmIdempotencyOutcomeTest {
    /** 业务超过十分钟仍只执行一次，保留期从实际完成时起算。 */
    @Test void retentionStartsAfterLongExecutionAndReplayUsesCurrentTrace() {
        var clock = new MutableClock();
        var calls = new AtomicInteger();
        var store = new InMemoryGmIdempotencyStore();
        var events = new ArrayList<GmAuditEvent>();
        var endpoint = endpoint(store, clock, calls, () -> {
            clock.now = clock.now.plusSeconds(1200);
            store.claim("other", "other", clock.instant(), Duration.ofSeconds(1));
        }, events);
        assertEquals("ZERO-OK", endpoint.handle(request("first")).code());
        clock.now = clock.now.plusSeconds(599);
        var replay = endpoint.handle(request("retry"));
        assertEquals("ZERO-OK", replay.code());
        assertEquals("retry", replay.traceId());
        assertEquals(1, calls.get());
        assertEquals(GmAuditPhase.REPLAYED, events.getLast().phase());
        clock.now = clock.now.plusSeconds(1);
        assertEquals("ZERO-OK", endpoint.handle(request("after-retention")).code());
        assertEquals(2, calls.get());
    }

    /** handler 内重入同键，仍在途且不重复执行。 */
    @Test void executingRequestCannotBeReclaimedByReentrantCall() {
        var store = new InMemoryGmIdempotencyStore();
        var clock = new MutableClock();
        var calls = new AtomicInteger();
        var events = new ArrayList<GmAuditEvent>();
        var holder = new GmOperationEndpoint[1];
        holder[0] = endpoint(store, clock, calls, () -> {
            clock.now = clock.now.plusSeconds(1200);
            assertEquals(GmErrorCode.IDEMPOTENCY_IN_PROGRESS.code(), holder[0].handle(request("nested")).code());
        }, events);
        assertEquals("ZERO-OK", holder[0].handle(request("outer")).code());
        assertEquals(1, calls.get());
        assertEquals(1, events.stream().filter(e -> e.phase() == GmAuditPhase.REJECTED).count());
    }

    /** claim 故障拒绝且不执行业务，保留可观测失败标记。 */
    @Test void claimFailureNeverExecutesAndIsAudited() {
        var calls = new AtomicInteger();
        var events = new ArrayList<GmAuditEvent>();
        var endpoint = endpoint(new FaultStore(true, false, false), new MutableClock(), calls, () -> { }, events);
        var response = endpoint.handle(request("claim"));
        assertEquals(GmErrorCode.IDEMPOTENCY_STORE_FAILED.code(), response.code());
        assertTrue(response.idempotencyFailed());
        assertEquals(GmBusinessCommitState.NOT_COMMITTED, response.businessCommitState());
        assertEquals(0, calls.get());
        assertEquals(GmAuditPhase.REJECTED, events.getLast().phase());
    }

    /** complete 写后响应丢失不得用 fail 覆盖已提交结果，也不能再次执行业务。 */
    @Test void completeFailurePreservesCommittedOutcomeAndDoesNotCallFail() {
        var store = new FaultStore(false, true, false);
        var calls = new AtomicInteger();
        var events = new ArrayList<GmAuditEvent>();
        var endpoint = endpoint(store, new MutableClock(), calls, () -> { }, events);
        var response = endpoint.handle(request("first"));
        assertEquals(GmErrorCode.IDEMPOTENCY_STORE_FAILED.code(), response.code());
        assertEquals(GmBusinessCommitState.COMMITTED, response.businessCommitState());
        assertNotNull(response.result());
        assertTrue(response.result().success());
        assertTrue(response.idempotencyFailed());
        assertFalse(response.auditFailed());
        assertEquals(GmAuditPhase.BOOKKEEPING_FAILED, events.getLast().phase());
        assertEquals(0, store.failCalls);
        assertEquals("ZERO-OK", endpoint.handle(request("second")).code());
        assertEquals(1, calls.get());
    }

    /** handler 失败与 fail 后端失败同时发生时保持原业务错误码。 */
    @Test void failedBookkeepingDoesNotHideBusinessFailure() {
        var store = new FaultStore(false, false, true);
        var events = new ArrayList<GmAuditEvent>();
        var endpoint = endpoint(store, new MutableClock(), new AtomicInteger(), () -> {
            throw group.zn.zero.core.error.ZeroException.of(GmErrorCode.COMMAND_RESULT_INVALID);
        }, events);
        var response = endpoint.handle(request("failure"));
        assertEquals(GmErrorCode.COMMAND_RESULT_INVALID.code(), response.code());
        assertTrue(response.idempotencyFailed());
        assertEquals(GmBusinessCommitState.UNKNOWN, response.businessCommitState());
        assertEquals(GmAuditPhase.BOOKKEEPING_FAILED, events.getLast().phase());
    }

    /** Error 保持同一实例，失败记账仅附为 suppressed。 */
    @Test void fatalErrorSurvivesFailedBookkeeping() {
        var primary = new AssertionError("original");
        var store = new FaultStore(false, false, true);
        var events = new ArrayList<GmAuditEvent>();
        var endpoint = endpoint(store, new MutableClock(), new AtomicInteger(), () -> { throw primary; }, events);
        assertSame(primary, assertThrows(AssertionError.class, () -> endpoint.handle(request("error"))));
        assertEquals(1, primary.getSuppressed().length);
        assertEquals(GmAuditPhase.EXECUTE_FAILED, events.getLast().phase());
    }

    private static GmOperationRequest request(String trace) {
        return new GmOperationRequest(GmCommandContext.simple("user", "192.0.2.1", trace),
                "/ping", "server", "", "", "key", false);
    }

    private static GmOperationEndpoint endpoint(GmIdempotencyStore store, Clock clock, AtomicInteger calls,
            Runnable action, List<GmAuditEvent> events) {
        var registry = new GmCommandRegistry();
        registry.register(new GmCommandDefinition(List.of("ping"), List.of(), "ping", GmCommandRisk.LOW, "", false),
                new GmCommandHandler() {
                    @Override public GmDryRunResult dryRun(GmCommandExecutionRequest request) {
                        return GmDryRunResult.accepted("ping", "ok", Map.of());
                    }
                    @Override public GmCommandExecutionResult execute(GmCommandExecutionRequest request) {
                        calls.incrementAndGet();
                        action.run();
                        return GmCommandExecutionResult.success("ping", "ok", Map.of());
                    }
                });
        var policy = new GmOperationAuthorizationPolicy(Set.of(), Set.of(), Set.of("192.0.2.1"), false, false);
        return new GmOperationEndpoint(GmCommandExecutor.systemClock(registry, events::add),
                new GmOperationAuthorizer(policy, e -> { }), store, clock, GmOperationFingerprint.processLocal());
    }

    /** 只在测试调用线程推进的可变时钟。 */
    private static final class MutableClock extends Clock {
        /** 测试当前时间。 */
        private Instant now = Instant.EPOCH;
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    /** 注入持久化前/后异常的参考后端。 */
    private static final class FaultStore implements GmIdempotencyStore {
        /** 实际内存存储。 */
        private final InMemoryGmIdempotencyStore delegate = new InMemoryGmIdempotencyStore();
        /** claim 故障开关。 */
        private final boolean claimFailure;
        /** complete 写后故障开关。 */
        private final boolean completeFailure;
        /** fail 故障开关。 */
        private final boolean failFailure;
        /** fail 调用次数。 */
        private int failCalls;
        private FaultStore(boolean claimFailure, boolean completeFailure, boolean failFailure) {
            this.claimFailure = claimFailure;
            this.completeFailure = completeFailure;
            this.failFailure = failFailure;
        }
        @Override public Claim claim(String key, String fingerprint, Instant now, Duration ttl) {
            if (claimFailure) throw new IllegalStateException("claim unavailable");
            return delegate.claim(key, fingerprint, now, ttl);
        }
        @Override public void complete(String key, String fingerprint, GmOperationResponse response, Instant expiresAt) {
            delegate.complete(key, fingerprint, response, expiresAt);
            if (completeFailure) throw new IllegalStateException("completion acknowledgement lost");
        }
        @Override public void fail(String key, String fingerprint, Instant expiresAt) {
            failCalls++;
            if (failFailure) throw new IllegalStateException("failure bookkeeping unavailable");
            delegate.fail(key, fingerprint, expiresAt);
        }
    }
}
