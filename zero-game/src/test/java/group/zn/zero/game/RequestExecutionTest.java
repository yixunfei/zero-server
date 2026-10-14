package group.zn.zero.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.scheduler.ExecutorActorScheduler;
import group.zn.zero.rpc.RpcCallContext;
import group.zn.zero.rpc.RpcCallOptions;
import group.zn.zero.security.SecurityContext;
import group.zn.zero.security.SecurityContextBridge;
import group.zn.zero.security.SecurityMetadataAssertion;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 验证可信入口跨受管 Actor 的身份隔离、签名第二跳和晚完成交付边界。 @author zn */
class RequestExecutionTest {
    /** 不同玩家经真实异步线程仍拥有原身份，RPC 签名可被验证，scope 结束后清理。 */
    @Test void actorScopesRemainIsolatedAndSignSecondHop() throws Exception {
        try (var threads = Executors.newVirtualThreadPerTaskExecutor(); var actor = new ExecutorActorScheduler(threads)) {
            var bridge = new ContextualActorScheduler(actor);
            var signer = SecurityMetadataAssertion.digest(new byte[32]);
            var client = new group.zn.zero.rpc.client.RpcClientFactory(new group.zn.zero.rpc.spi.RpcTransport() {
                @Override public String name() { return "verified-second-hop"; }
                @Override public java.util.concurrent.CompletionStage<group.zn.zero.rpc.RpcResponse> request(
                        final group.zn.zero.rpc.RpcRequest request) {
                    return SecurityMetadataAssertion.verifier(signer).verify(request.securityMetadata(), Instant.now())
                            .thenApply(identity -> {
                                assertEquals("trace-" + identity.subject(), request.traceId());
                                assertEquals("player-replies", request.replyTopic());
                                assertTrue(request.timeoutAt().isBefore(Instant.now().plusSeconds(6)));
                                return new group.zn.zero.rpc.RpcResponse(request.correlationId(), request.traceId(),
                                        group.zn.zero.core.error.SystemErrorCode.OK, new byte[0]);
                            });
                }
                @Override public java.util.concurrent.CompletionStage<Void> oneway(final group.zn.zero.rpc.RpcRequest request) {
                    throw new AssertionError("request/response expected");
                }
            }, new group.zn.zero.rpc.codec.RpcCodecRegistry(), RpcCallOptions.defaults(), signer).create(SecondHop.class);
            bridge.register(String.class, (context, message) -> {
                String uid = (String) message.payload();
                assertEquals(uid, SecurityContextBridge.current().orElseThrow().subject());
                assertEquals("trace-" + uid, RpcCallContext.current().resolveTraceId(RpcCallOptions.defaults()));
                return client.call().thenAccept(result -> assertTrue(result.success()));
            });
            var a = scope("1", Duration.ofSeconds(5));
            var b = scope("2", Duration.ofSeconds(5));
            var first = a.call(() -> bridge.dispatch(new ActorMessage(LaneKey.player("1"), "1")));
            var second = b.call(() -> bridge.dispatch(new ActorMessage(LaneKey.player("2"), "2")));
            CompletableFuture.allOf(first.toCompletableFuture(), second.toCompletableFuture()).get(3, TimeUnit.SECONDS);
            assertTrue(SecurityContextBridge.current().isEmpty());
            assertTrue(RequestExecution.current().isEmpty());
        }
    }

    /** 取消只影响交付，物理完成前同 lane 的下一条消息不能执行。 */
    @Test void cancellationDoesNotReleasePhysicalActorWork() throws Exception {
        try (var threads = Executors.newVirtualThreadPerTaskExecutor(); var actor = new ExecutorActorScheduler(threads)) {
            var bridge = new ContextualActorScheduler(actor);
            var entered = new CompletableFuture<Void>();
            var pending = new CompletableFuture<Void>();
            var next = new CompletableFuture<Void>();
            bridge.register(String.class, (context, message) -> {
                if (message.payload().equals("first")) { entered.complete(null); return pending; }
                assertTrue(SecurityContextBridge.current().isEmpty());
                next.complete(null); return CompletableFuture.completedFuture(null);
            });
            var scope = scope("1", Duration.ofSeconds(5));
            var physical = scope.call(() -> bridge.dispatch(new ActorMessage(LaneKey.player("1"), "first")));
            entered.get(3, TimeUnit.SECONDS);
            var delivered = scope.deliver(physical);
            delivered.cancel(false);
            var queued = bridge.dispatch(new ActorMessage(LaneKey.player("1"), "next"));
            assertFalse(next.isDone());
            pending.complete(null);
            queued.toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertTrue(next.isDone());
            assertTrue(delivered.isCancelled());
            assertThrows(RuntimeException.class, () -> scope.call(() -> "late"));
        }
    }

    /** 过期身份/期限在准入及延迟完成时拒绝，不能在回调线程恢复成有效身份。 */
    @Test void expiryRejectsLateDelivery() {
        var ticker = new java.util.concurrent.atomic.AtomicLong();
        var scope = new RequestExecution(scope("1", Duration.ofSeconds(5)).identity(),
                Duration.ofSeconds(1), ticker::get);
        var pending = new CompletableFuture<String>();
        var delivery = scope.deliver(pending);
        ticker.set(TimeUnit.SECONDS.toNanos(2));
        pending.complete("committed");
        assertThrows(java.util.concurrent.CompletionException.class, delivery::join);
        assertTrue(SecurityContextBridge.current().isEmpty());
        Instant now = Instant.now();
        var expired = new SecurityContext("1", now.minusSeconds(2), now.minusSeconds(1), "test", "local", "local",
                "trace", "request", Set.of(), Map.of());
        assertThrows(RuntimeException.class, () -> SecurityContextBridge.with(expired,
                () -> RequestExecution.capture(Duration.ofSeconds(1))));
    }

    /** 排队期间超时的请求不能执行业务；失败后的后台消息不能继承身份。 */
    @Test void queuedExpiryRejectsBeforeBusinessAndCleansScope() throws Exception {
        try (var threads = Executors.newVirtualThreadPerTaskExecutor(); var actor = new ExecutorActorScheduler(threads)) {
            var bridge = new ContextualActorScheduler(actor);
            var entered = new CompletableFuture<Void>();
            var pending = new CompletableFuture<Void>();
            var invoked = new java.util.concurrent.atomic.AtomicInteger();
            bridge.register(String.class, (context, message) -> {
                if (message.payload().equals("hold")) { entered.complete(null); return pending; }
                assertTrue(SecurityContextBridge.current().isEmpty());
                invoked.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });
            var lane = LaneKey.player("1");
            bridge.dispatch(new ActorMessage(lane, "hold"));
            entered.get(3, TimeUnit.SECONDS);
            var ticker = new java.util.concurrent.atomic.AtomicLong();
            var scope = new RequestExecution(scope("1", Duration.ofSeconds(5)).identity(), Duration.ofSeconds(1), ticker::get);
            var expired = scope.call(() -> bridge.dispatch(new ActorMessage(lane, "expired")));
            ticker.set(TimeUnit.SECONDS.toNanos(2));
            pending.complete(null);
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> expired.toCompletableFuture().get(3, TimeUnit.SECONDS));
            bridge.dispatch(new ActorMessage(lane, "clean")).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(1, invoked.get());
        }
    }

    /** 实际 RPC 代理使用的第二跳契约。 @author zn */
    @group.zn.zero.rpc.common.RpcService(name = "player-second-hop")
    interface SecondHop {
        /** @return 无业务负载的可验证响应；无阻塞。 */
        @group.zn.zero.rpc.common.RpcMethod(id = 1)
        java.util.concurrent.CompletionStage<group.zn.zero.rpc.common.RpcResult<Void>> call();
    }

    private static RequestExecution scope(final String uid, final Duration budget) {
        Instant now = Instant.now();
        return SecurityContextBridge.with(new SecurityContext(uid, now, now.plusSeconds(60), "test", "local", "local",
                "trace-" + uid, "request-" + uid, Set.of("reward"), Map.of()), () -> RpcCallContext.with(RpcCallContext.current().withReplyTopic("player-replies"),
                        () -> RequestExecution.capture(budget)));
    }
}
