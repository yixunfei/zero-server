package __PACKAGE__;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import __PACKAGE__.generated.dto.GameLoginProtocolDTO;
import __PACKAGE__.generated.dto.codec.GameLoginProtocolDTOCodec;
import __PACKAGE__.generated.protocol.ProtocolIds;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.player.PlayerService;
import group.zn.zero.scene.SceneService;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 实际 BO 分发必须保留异步失败与完成时序，禁止提前成功或跨请求串结果。 @author zn */
class LocalGameAsyncTest {
    @Test
    void metricsAreCumulativeAndFailuresRetainTrace() {
        var records = new java.util.ArrayList<group.zn.zero.log.ZeroLogRecord>();
        var monitor = group.zn.zero.monitor.MonitorRuntime.createDefault();
        var observation = new LocalGameObservation(records::add, monitor,
                new group.zn.zero.log.LogSource("local-game", "test", "business"));
        observation.accepted("login", "trace-one");
        observation.observe("login", "trace-one", CompletableFuture.completedFuture(1)).toCompletableFuture().join();
        observation.accepted("login", "trace-two");
        observation.observe("login", "trace-two", CompletableFuture.completedFuture(2)).toCompletableFuture().join();
        assertTrue(monitor.exportPrometheus().contains("local_game_command_total{action=\"login\",result=\"success\"} 2.0"));
        assertThrows(CompletionException.class, () -> observation.observe("move", "trace-fail",
                CompletableFuture.failedFuture(ZeroException.of(SystemErrorCode.INVALID_ARGUMENT)))
                .toCompletableFuture().join());
        var failure = records.getLast();
        assertEquals("trace-fail", failure.traceId());
        assertEquals(group.zn.zero.log.LogResult.FAILURE, failure.logOperation().result());
        assertEquals(2, observation.completedCount());
        assertEquals(1, observation.failedCount());
    }

    @Test
    void pendingBusinessDoesNotCompleteDispatchAndFailureIsPreserved() {
        CompletableFuture<Object> pending = new CompletableFuture<>();
        PlayerService players = (PlayerService) Proxy.newProxyInstance(PlayerService.class.getClassLoader(),
                new Class<?>[]{PlayerService.class}, (proxy, method, args) -> pending);
        SceneService scenes = unusedScenes();
        LocalGameObservation observation = new LocalGameObservation();
        LocalGameFlow flow = new LocalGameFlow(players, scenes, observation);
        var dispatched = flow.dispatch(ProtocolIds.GAME_LOGIN_PROTOCOL, GameLoginProtocolDTOCodec.INSTANCE, login())
                .toCompletableFuture();
        assertFalse(dispatched.isDone());
        assertEquals(0, observation.completedCount());
        pending.completeExceptionally(ZeroException.of(SystemErrorCode.INVALID_ARGUMENT));
        var failure = assertThrows(CompletionException.class, dispatched::join);
        assertEquals(SystemErrorCode.INVALID_ARGUMENT, ((ZeroException) failure.getCause()).errorCode());
        assertEquals(1, observation.failedCount());
        assertEquals(0, observation.completedCount());
    }

    @Test
    void sameTraceRequestsCompleteIndependentlyInReverseOrder() {
        var first = new CompletableFuture<Object>();
        var second = new CompletableFuture<Object>();
        AtomicInteger calls = new AtomicInteger();
        PlayerService players = (PlayerService) Proxy.newProxyInstance(PlayerService.class.getClassLoader(),
                new Class<?>[]{PlayerService.class}, (proxy, method, args) -> calls.getAndIncrement() == 0 ? first : second);
        var flow = new LocalGameFlow(players, unusedScenes(), new LocalGameObservation());
        var a = flow.dispatch(ProtocolIds.GAME_LOGIN_PROTOCOL, GameLoginProtocolDTOCodec.INSTANCE, login()).toCompletableFuture();
        var b = flow.dispatch(ProtocolIds.GAME_LOGIN_PROTOCOL, GameLoginProtocolDTOCodec.INSTANCE, login()).toCompletableFuture();
        var secondResult = new group.zn.zero.player.PlayerLoginResult("second", 2, "s2", "shared-trace");
        second.complete(secondResult);
        assertEquals(secondResult, b.join());
        assertFalse(a.isDone());
        var firstResult = new group.zn.zero.player.PlayerLoginResult("first", 1, "s1", "shared-trace");
        first.complete(firstResult);
        assertEquals(firstResult, a.join());
    }

    @Test
    void synchronousServiceFailureAndUnknownProtocolDoNotDisappear() {
        PlayerService players = (PlayerService) Proxy.newProxyInstance(PlayerService.class.getClassLoader(),
                new Class<?>[]{PlayerService.class}, (proxy, method, args) -> { throw new IllegalArgumentException("private"); });
        var flow = new LocalGameFlow(players, unusedScenes(), new LocalGameObservation());
        var failure = assertThrows(CompletionException.class, () -> flow.dispatch(ProtocolIds.GAME_LOGIN_PROTOCOL,
                GameLoginProtocolDTOCodec.INSTANCE, login()).toCompletableFuture().join());
        assertInstanceOf(ZeroException.class, failure.getCause());
        assertThrows(CompletionException.class, () -> flow.dispatch(-1, new byte[0]).toCompletableFuture().join());
    }

    private static SceneService unusedScenes() {
        return (SceneService) Proxy.newProxyInstance(SceneService.class.getClassLoader(), new Class<?>[]{SceneService.class},
                (proxy, method, args) -> { throw new AssertionError("scene must not be called"); });
    }

    private static GameLoginProtocolDTO login() {
        GameLoginProtocolDTO request = new GameLoginProtocolDTO();
        request.accountId = "local-account";
        request.token = "local-token";
        request.traceId = "shared-trace";
        return request;
    }
}
