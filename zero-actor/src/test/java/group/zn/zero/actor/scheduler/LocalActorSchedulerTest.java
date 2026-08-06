package group.zn.zero.actor.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/**
 * 本地 Actor 调度器测试。
 *
 * @author zn
 */
class LocalActorSchedulerTest {

    /**
     * 验证同一 lane 按提交顺序执行。
     */
    @Test
    void dispatchShouldKeepOrderInSameLane() {
        ActorScheduler scheduler = new LocalActorScheduler();
        List<String> calls = new ArrayList<>();
        LaneKey laneKey = LaneKey.player("p1");

        scheduler.register(String.class, ActorHandler.sync((context, message) -> calls.add((String) message.payload())));

        scheduler.dispatch(new ActorMessage("m1", laneKey, "trace-1", "a")).toCompletableFuture().join();
        scheduler.dispatch(new ActorMessage("m2", laneKey, "trace-1", "b")).toCompletableFuture().join();
        scheduler.dispatch(new ActorMessage("m3", laneKey, "trace-1", "c")).toCompletableFuture().join();

        assertEquals(List.of("a", "b", "c"), calls);
    }

    /**
     * 验证父类型处理器可以处理子类型消息体。
     */
    @Test
    void dispatchShouldUseAssignableHandler() {
        ActorScheduler scheduler = new LocalActorScheduler();
        List<Number> calls = new ArrayList<>();

        scheduler.register(Number.class, ActorHandler.sync((context, message) -> calls.add((Number) message.payload())));

        scheduler.dispatch(new ActorMessage(LaneKey.entity("e1"), Integer.valueOf(7))).toCompletableFuture().join();

        assertEquals(List.of(Integer.valueOf(7)), calls);
    }

    /**
     * 验证处理器缺失时返回统一错误码。
     */
    @Test
    void dispatchShouldFailWhenHandlerMissing() {
        ActorScheduler scheduler = new LocalActorScheduler();

        ZeroException ex = assertThrows(
                ZeroException.class,
                () -> scheduler.dispatch(new ActorMessage(LaneKey.player("p1"), "payload")));

        assertEquals(SystemErrorCode.INVALID_ARGUMENT, ex.errorCode());
    }

    /**
     * 验证处理器异常会绑定统一错误码传递给调度结果。
     */
    @Test
    void dispatchShouldPropagateHandlerFailure() {
        ActorScheduler scheduler = new LocalActorScheduler();
        ZeroException failure = ZeroException.of(SystemErrorCode.SYSTEM_ERROR, "boom", null);

        scheduler.register(String.class, ActorHandler.sync((context, message) -> {
            throw failure;
        }));

        CompletionException ex = assertThrows(
                CompletionException.class,
                () -> scheduler.dispatch(new ActorMessage(LaneKey.player("p1"), "payload")).toCompletableFuture().join());

        assertEquals(failure, ex.getCause());
    }

    /**
     * 验证取消注册后处理器不再生效。
     */
    @Test
    void closeSubscriptionShouldRemoveHandler() {
        ActorScheduler scheduler = new LocalActorScheduler();
        ActorSubscription subscription = scheduler.register(
                String.class,
                ActorHandler.sync((context, message) -> {
                }));

        subscription.close();

        ZeroException ex = assertThrows(
                ZeroException.class,
                () -> scheduler.dispatch(new ActorMessage(LaneKey.player("p1"), "payload")));

        assertInstanceOf(ZeroException.class, ex);
        assertEquals(SystemErrorCode.INVALID_ARGUMENT, ex.errorCode());
    }
}
