package group.zn.zero.player;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.scheduler.ActorSubscription;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import group.zn.zero.cache.InMemoryCacheService;
import group.zn.zero.data.repository.InMemoryCrudRepository;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

/** 异步数据读取后的调度失败必须完成玩家请求，不能丢入无人观察的阶段。 @author zn */
class PlayerDispatchFailureTest {
    /** Actor 提交严重失败不能使登录或加载请求永久挂起。 */
    @Test void actorDispatchErrorCompletesLoginAndLoadRequests() {
        var failure = new AssertionError("actor executor unavailable");
        try (var service = service(new FailingScheduler(failure), Runnable::run)) {
            assertFailure(service.login(new PlayerLoginRequest("account", "token", "trace"))
                    .toCompletableFuture(), failure);
            assertFailure(service.loadPlayer(new PlayerLoadRequest(7, "trace")).toCompletableFuture(), failure);
        }
    }

    /** IO 执行器同步提交失败也必须结束已创建的结果信号。 */
    @Test void ioSubmissionErrorCompletesLoginAndLoadRequests() {
        var failure = new AssertionError("IO executor unavailable");
        try (var service = service(new LocalActorScheduler(), command -> { throw failure; })) {
            assertFailure(service.login(new PlayerLoginRequest("account", "token", "trace"))
                    .toCompletableFuture(), failure);
            assertFailure(service.loadPlayer(new PlayerLoadRequest(7, "trace")).toCompletableFuture(), failure);
        }
    }

    private static LocalPlayerService service(final ActorScheduler scheduler, final Executor executor) {
        var repository = new InMemoryCrudRepository<Long, PlayerProfile>();
        repository.save(new PlayerProfile(7, "known", true)).toCompletableFuture().join();
        return new LocalPlayerService(scheduler, request -> 7, repository, new InMemoryCacheService<>(), executor);
    }

    private static void assertFailure(final CompletableFuture<?> result, final Error cause) {
        assertTrue(result.isDone(), "request must not remain pending after dispatch failure");
        assertSame(cause, assertThrows(CompletionException.class, result::join).getCause());
    }

    /** 用于独立复现 SPI 的同步严重失败。 @author zn */
    private static final class FailingScheduler implements ActorScheduler {
        /** 固定的提交失败。 */
        private final Error failure;

        private FailingScheduler(final Error failure) { this.failure = failure; }

        /** 允许服务注册；无状态，返回幂等的测试注销句柄。 */
        @Override public ActorSubscription register(final Class<?> payloadType, final ActorHandler handler) {
            return () -> { };
        }

        /** 不准入业务消息，同步模拟执行器失败。 */
        @Override public CompletionStage<Void> dispatch(final ActorMessage message) { throw failure; }
    }
}
