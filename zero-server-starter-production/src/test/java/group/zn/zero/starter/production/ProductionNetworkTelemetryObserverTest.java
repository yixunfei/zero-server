package group.zn.zero.starter.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.log.InMemoryLogSink;
import group.zn.zero.log.LogPipeline;
import group.zn.zero.log.LogResult;
import group.zn.zero.log.ZeroLogRecord;
import group.zn.zero.monitor.InMemoryMetricRegistry;
import group.zn.zero.monitor.MetricDefinition;
import group.zn.zero.monitor.MetricRegistry;
import group.zn.zero.monitor.MetricSample;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.net.lifecycle.ConnectionLifecycleEventType;
import group.zn.zero.net.lifecycle.ConnectionLifecycleObservation;
import group.zn.zero.net.lifecycle.ConnectionLifecycleResult;
import group.zn.zero.net.lifecycle.ConnectionLifecycleState;
import group.zn.zero.net.lifecycle.ConnectionRejectionReason;
import group.zn.zero.net.lifecycle.NetworkRateLimitScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * production 网络遥测 observer 测试。
 *
 * @author zn
 */
class ProductionNetworkTelemetryObserverTest {

    /**
     * PNFT-09：验证指标只使用已确认的低基数标签，且不会携带连接、玩家、IP 或 token 标识。
     */
    @Test
    void pnft09MetricLabelsShouldRemainLowCardinality() {
        InMemoryMetricRegistry registry = new InMemoryMetricRegistry();
        InMemoryLogSink logSink = new InMemoryLogSink();
        ProductionNetworkTelemetryObserver observer = new ProductionNetworkTelemetryObserver(
                new LogPipeline(List.of(), logSink),
                registry);
        observer.onEvent(new ConnectionLifecycleObservation(
                Instant.parse("2026-07-27T00:00:00Z"),
                "game-gateway",
                "tcp",
                "trace-sensitive-value",
                "connection-sensitive-value",
                "192.0.2.0:*",
                ConnectionLifecycleState.ESTABLISHED,
                ConnectionLifecycleEventType.AUTH_SUCCEEDED,
                ConnectionLifecycleResult.SUCCEEDED,
                ConnectionRejectionReason.NONE,
                NetworkRateLimitScope.NONE,
                null,
                1_000_000L,
                0));

        observer.onEvent(new ConnectionLifecycleObservation(
                Instant.parse("2026-07-27T00:00:01Z"),
                "game-gateway",
                "tcp",
                "trace-rate-limited",
                "connection-rate-limited",
                "192.0.2.1:*",
                ConnectionLifecycleState.REJECTED,
                ConnectionLifecycleEventType.RATE_LIMIT_EXCEEDED,
                ConnectionLifecycleResult.REJECTED,
                ConnectionRejectionReason.CONNECTION_RATE_LIMITED,
                NetworkRateLimitScope.CONNECTION,
                NetErrorCode.RATE_LIMITED,
                0L,
                0));

        Set<String> allowed = ProductionNetworkTelemetryObserver.allowedMetricLabels();
        Set<String> forbidden = Set.of(
                "playerId", "connectionId", "ip", "token", "roomId", "sceneId", "traceId");
        assertFalse(registry.samples().isEmpty());
        for (MetricSample sample : registry.samples()) {
            assertTrue(allowed.containsAll(sample.labels().keySet()));
            assertTrue(sample.labels().keySet().stream().noneMatch(forbidden::contains));
            assertTrue(sample.labels().values().stream()
                    .noneMatch(value -> value.contains("sensitive") || value.contains("192.0.2")));
        }
        assertEquals(2, logSink.records().size());
        ZeroLogRecord succeeded = logSink.records().getFirst();
        ZeroLogRecord rejected = logSink.records().get(1);
        assertSame(LogResult.SUCCESS, succeeded.result());
        assertEquals("[REDACTED]", succeeded.fields().get("remoteAddress"));
        assertSame(LogResult.REJECTED, rejected.result());
        assertSame(NetErrorCode.RATE_LIMITED, rejected.errorCode());
    }

    /**
     * 验证活跃连接 gauge 按 listener/protocol 独立计数，并将重复或未知关闭事件钳制为零。
     */
    @Test
    void activeConnectionsShouldBeIsolatedByListenerAndProtocol() {
        InMemoryMetricRegistry registry = new InMemoryMetricRegistry();
        ProductionNetworkTelemetryObserver observer = new ProductionNetworkTelemetryObserver(
                new LogPipeline(List.of(), new InMemoryLogSink()),
                registry);

        observer.onEvent(connectionObservation(
                "gateway-a", "tcp", ConnectionLifecycleEventType.CHANNEL_ACCEPTED));
        observer.onEvent(connectionObservation(
                "gateway-a", "tcp", ConnectionLifecycleEventType.CHANNEL_ACCEPTED));
        observer.onEvent(connectionObservation(
                "gateway-a", "websocket", ConnectionLifecycleEventType.CHANNEL_ACCEPTED));
        observer.onEvent(connectionObservation(
                "gateway-b", "tcp", ConnectionLifecycleEventType.CHANNEL_ACCEPTED));
        observer.onEvent(connectionObservation(
                "gateway-a", "tcp", ConnectionLifecycleEventType.CHANNEL_CLOSED));
        observer.onEvent(connectionObservation(
                "gateway-a", "tcp", ConnectionLifecycleEventType.CHANNEL_CLOSED));
        observer.onEvent(connectionObservation(
                "gateway-a", "tcp", ConnectionLifecycleEventType.CHANNEL_CLOSED));
        observer.onEvent(connectionObservation(
                "gateway-c", "quic", ConnectionLifecycleEventType.CHANNEL_CLOSED));

        List<MetricSample> activeSamples = registry.samples().stream()
                .filter(sample -> ProductionNetworkTelemetryObserver.CONNECTIONS_ACTIVE.equals(sample.name()))
                .toList();
        assertEquals(List.of(1D, 2D, 1D, 1D, 1D, 0D, 0D, 0D), activeSamples.stream()
                .map(MetricSample::value)
                .toList());
        assertEquals(List.of(
                Map.of("listener", "gateway-a", "protocol", "tcp"),
                Map.of("listener", "gateway-a", "protocol", "tcp"),
                Map.of("listener", "gateway-a", "protocol", "websocket"),
                Map.of("listener", "gateway-b", "protocol", "tcp"),
                Map.of("listener", "gateway-a", "protocol", "tcp"),
                Map.of("listener", "gateway-a", "protocol", "tcp"),
                Map.of("listener", "gateway-a", "protocol", "tcp"),
                Map.of("listener", "gateway-c", "protocol", "quic")),
                activeSamples.stream().map(MetricSample::labels).toList());
    }

    /**
     * 验证同一时序的计数更新和 gauge 发布整体线性化，不会在并发交错后留下陈旧末值。
     *
     * @throws InterruptedException 当前测试线程等待受控交错或 worker 结束时被中断。
     */
    @Test
    void activeConnectionGaugeShouldPublishSameSeriesUpdatesInCounterOrder() throws InterruptedException {
        BlockingActiveGaugeRegistry registry = new BlockingActiveGaugeRegistry();
        ProductionNetworkTelemetryObserver observer = new ProductionNetworkTelemetryObserver(
                new LogPipeline(List.of(), new InMemoryLogSink()),
                registry);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch secondStarted = new CountDownLatch(1);
        Thread first = observerThread(
                "active-gauge-first",
                () -> observer.onEvent(connectionObservation(
                        "gateway-a", "tcp", ConnectionLifecycleEventType.CHANNEL_ACCEPTED)),
                failure);
        Thread second = observerThread(
                "active-gauge-second",
                () -> {
                    secondStarted.countDown();
                    observer.onEvent(connectionObservation(
                            "gateway-a", "tcp", ConnectionLifecycleEventType.CHANNEL_ACCEPTED));
                },
                failure);

        first.start();
        try {
            assertTrue(registry.awaitFirstActiveRecord(5, TimeUnit.SECONDS));
            second.start();
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertFalse(registry.awaitSecondActiveRecord(200, TimeUnit.MILLISECONDS));
        } finally {
            registry.releaseFirstActiveRecord();
            first.join(5_000L);
            if (second.getState() != Thread.State.NEW) {
                second.join(5_000L);
            }
        }

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        if (failure.get() != null) {
            throw new AssertionError("observer worker failed", failure.get());
        }
        assertTrue(registry.awaitSecondActiveRecord(5, TimeUnit.SECONDS));
        assertEquals(List.of(1D, 2D), registry.samples().stream()
                .filter(sample -> ProductionNetworkTelemetryObserver.CONNECTIONS_ACTIVE.equals(sample.name()))
                .map(MetricSample::value)
                .toList());
    }

    /**
     * 创建捕获异步失败的测试线程。
     *
     * @param name 线程名；不可为空。
     * @param action 测试动作；不可为空。
     * @param failure 首个失败保存位置；不可为空。
     * @return 尚未启动、非空的测试线程。
     */
    private Thread observerThread(
            final String name,
            final Runnable action,
            final AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try {
                action.run();
            } catch (Throwable exception) {
                failure.compareAndSet(null, exception);
            }
        }, name);
    }

    /**
     * 创建仅用于活跃连接计数测试的生命周期事件。
     *
     * @param listener 监听入口名。
     * @param protocol 传输协议名。
     * @param event 接入或关闭事件。
     * @return 不可变、非空、线程安全的观测事件。
     */
    private ConnectionLifecycleObservation connectionObservation(
            final String listener,
            final String protocol,
            final ConnectionLifecycleEventType event) {
        ConnectionLifecycleState state = event == ConnectionLifecycleEventType.CHANNEL_ACCEPTED
                ? ConnectionLifecycleState.ACCEPTED
                : ConnectionLifecycleState.CLOSED;
        return new ConnectionLifecycleObservation(
                Instant.parse("2026-08-04T00:00:00Z"),
                listener,
                protocol,
                "trace-active-series",
                "connection-active-series",
                "192.0.2.0:*",
                state,
                event,
                ConnectionLifecycleResult.OBSERVED,
                ConnectionRejectionReason.NONE,
                NetworkRateLimitScope.NONE,
                null,
                0L,
                0);
    }

    /**
     * 在第一条 active gauge 样本写入点制造可控阻塞的线程安全测试注册表。
     */
    private static final class BlockingActiveGaugeRegistry implements MetricRegistry {

        /** 实际保存定义和样本的线程安全注册表。 */
        private final InMemoryMetricRegistry delegate = new InMemoryMetricRegistry();
        /** 第一条 active gauge 已进入记录调用。 */
        private final CountDownLatch firstActiveRecord = new CountDownLatch(1);
        /** 允许第一条 active gauge 继续写入。 */
        private final CountDownLatch releaseFirstActiveRecord = new CountDownLatch(1);
        /** 第二条 active gauge 已进入记录调用。 */
        private final CountDownLatch secondActiveRecord = new CountDownLatch(1);
        /** active gauge 记录调用次数。 */
        private final AtomicInteger activeRecordCalls = new AtomicInteger();

        /**
         * 注册指标定义。
         *
         * @param definition 指标定义；不可为空。
         */
        @Override
        public void register(final MetricDefinition definition) {
            delegate.register(definition);
        }

        /**
         * 记录样本；第一条 active gauge 在委托写入前等待测试线程放行。
         *
         * @param sample 指标样本；不可为空。
         */
        @Override
        public void record(final MetricSample sample) {
            if (ProductionNetworkTelemetryObserver.CONNECTIONS_ACTIVE.equals(sample.name())) {
                int call = activeRecordCalls.incrementAndGet();
                if (call == 1) {
                    firstActiveRecord.countDown();
                    awaitRelease();
                } else if (call == 2) {
                    secondActiveRecord.countDown();
                }
            }
            delegate.record(sample);
        }

        /**
         * 返回定义快照。
         *
         * @return 不可变、有序、非空、线程安全的定义列表。
         */
        @Override
        public List<MetricDefinition> definitions() {
            return delegate.definitions();
        }

        /**
         * 返回样本快照。
         *
         * @return 不可变、有序、可能为空、线程安全的样本列表。
         */
        @Override
        public List<MetricSample> samples() {
            return delegate.samples();
        }

        private boolean awaitFirstActiveRecord(final long timeout, final TimeUnit unit)
                throws InterruptedException {
            return firstActiveRecord.await(timeout, unit);
        }

        private boolean awaitSecondActiveRecord(final long timeout, final TimeUnit unit)
                throws InterruptedException {
            return secondActiveRecord.await(timeout, unit);
        }

        private void releaseFirstActiveRecord() {
            releaseFirstActiveRecord.countDown();
        }

        private void awaitRelease() {
            try {
                if (!releaseFirstActiveRecord.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("first active gauge release timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("first active gauge wait was interrupted", exception);
            }
        }
    }
}
