package group.zn.zero.examples.business;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.cache.InMemoryCacheService;
import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.config.ZeroConfigLoader;
import group.zn.zero.data.repository.RepositoryRequest;
import group.zn.zero.event.EventType;
import group.zn.zero.event.bus.InMemoryEventBus;
import group.zn.zero.event.deadletter.InMemoryDeadLetterSink;
import group.zn.zero.game.ActorRequestExecutor;
import group.zn.zero.runtime.actor.ActorRuntime;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.data.DataRuntime;
import group.zn.zero.runtime.postgresql.PostgresqlRuntime;
import group.zn.zero.runtime.production.ProductionAssembly;
import group.zn.zero.runtime.production.ZeroProductionRuntimeConfigKeys;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** 显式运行的真实 PostgreSQL 验收：关闭重建 runtime 后恢复自定义库存与待通知状态。 @author zn */
class DailyRewardPersistenceExternalIT {
    /** 故障通知留在同一真实聚合，恢复后去重响应和重放均不重复发奖。 */
    @Test void repositoryCatalogPersistsAcrossRuntimeRecreation() throws Exception {
        long uid = 1_000_000L + java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L);
        try {
            try (GameRuntime first = runtime()) {
                first.start();
                var repository = first.require(DataRuntime.REPOSITORIES).create(new RepositoryRequest<>("game", DailyRewardRepository.DEFINITION));
                var events = new InMemoryEventBus(new InMemoryDeadLetterSink(), Clock.systemUTC(), Runnable::run);
                try (var ignored = events.register(EventType.INTERNAL, event -> CompletableFuture.failedFuture(new IllegalStateException("offline")));
                        var actors = new ActorRequestExecutor(first.require(ActorRuntime.ACTOR_SCHEDULER))) {
                    var service = new DailyRewardService(repository, new InMemoryCacheService<>(), events, Clock.systemUTC());
                    var flow = new DailyRewardFlow(new DailyRewardBO(actors, service));
                    var response = flow.handle(DailyRewardFlowTest.request(uid), DailyRewardFlowTest.scope(uid)).toCompletableFuture().join();
                    assertTrue(response.flags() != 0);
                    var saved = repository.findById(uid).toCompletableFuture().join().orElseThrow();
                    assertEquals(100, saved.quantity()); assertTrue(saved.pendingNotification());
                }
            }
            try (GameRuntime recovered = runtime()) {
                recovered.start();
                var repository = recovered.require(DataRuntime.REPOSITORIES).create(new RepositoryRequest<>("game", DailyRewardRepository.DEFINITION));
                var events = new InMemoryEventBus(new InMemoryDeadLetterSink(), Clock.systemUTC(), Runnable::run);
                var consumed = new java.util.HashSet<String>();
                var delivered = new java.util.concurrent.CountDownLatch(1);
                try (var ignored = events.register(EventType.INTERNAL, event -> {
                    consumed.add(event.eventId()); delivered.countDown(); return CompletableFuture.completedFuture(null);
                }); var actors = new ActorRequestExecutor(recovered.require(ActorRuntime.ACTOR_SCHEDULER))) {
                    var service = new DailyRewardService(repository, new InMemoryCacheService<>(), events, Clock.systemUTC());
                    var flow = new DailyRewardFlow(new DailyRewardBO(actors, service));
                    var response = flow.handle(DailyRewardFlowTest.request(uid), DailyRewardFlowTest.scope(uid)).toCompletableFuture().join();
                    assertEquals("DUPLICATE", DailyRewardFlowTest.responseStatus(response));
                    try (var replay = new DailyRewardNotificationReplay(repository, service, actors,
                            recovered.require(group.zn.zero.runtime.bootstrap.RuntimeBasics.EXECUTORS), java.time.Duration.ofMillis(20))) {
                        replay.start();
                        assertTrue(delivered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    }
                    assertFalse(service.replayNotification(uid).toCompletableFuture().join());
                    assertEquals(1, consumed.size());
                    assertEquals(100, repository.findById(uid).toCompletableFuture().join().orElseThrow().quantity());
                }
            }
        } finally {
            try (GameRuntime cleanup = runtime()) {
                cleanup.start();
                cleanup.require(DataRuntime.REPOSITORIES).create(new RepositoryRequest<>("game", DailyRewardRepository.DEFINITION))
                        .deleteById(uid).toCompletableFuture().join();
            }
        }
    }

    private static GameRuntime runtime() {
        var values = new LinkedHashMap<>(ZeroConfigLoader.loadStandard().asMap());
        values.put(ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_POSTGRESQL_ENABLED, "true");
        return ProductionAssembly.builder("external-test", new MapZeroConfig(values)).install(PostgresqlRuntime.module())
                .install(ActorRuntime.module()).install(DataRuntime.repositories(Map.of("game", "postgresql"))).build();
    }
}
