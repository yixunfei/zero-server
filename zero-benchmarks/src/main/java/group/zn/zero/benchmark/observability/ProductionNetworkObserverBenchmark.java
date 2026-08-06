package group.zn.zero.benchmark.observability;

import group.zn.zero.log.LogPipeline;
import group.zn.zero.log.ZeroLogRecord;
import group.zn.zero.monitor.InMemoryMetricRegistry;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.net.lifecycle.ConnectionLifecycleEventType;
import group.zn.zero.net.lifecycle.ConnectionLifecycleObservation;
import group.zn.zero.net.lifecycle.ConnectionLifecycleResult;
import group.zn.zero.net.lifecycle.ConnectionLifecycleState;
import group.zn.zero.net.lifecycle.ConnectionRejectionReason;
import group.zn.zero.net.lifecycle.NetworkRateLimitScope;
import group.zn.zero.starter.production.ProductionNetworkTelemetryObserver;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * 生产网络生命周期 observer 的 current / candidate 方向性基准。
 *
 * <p>基准使用真实 {@link LogPipeline}、{@link InMemoryMetricRegistry} 和
 * {@link ProductionNetworkTelemetryObserver}，但不启动端口、线程、Docker 或外部组件。
 * 每次调用后的样本清理由 JMH invocation teardown 执行，不计入被测方法且避免样本集合无限增长。
 * 终端日志 sink 只保留最后一条记录，用于阻止结果被消除并保持内存有界。迁移到
 * {@code LogAppender} 后仍保留相同场景、固定 observation、调用顺序和清理边界。</p>
 *
 * <p>迁移前 current observer 直接写 terminal {@code LogSink}，candidate 则经过不可绕过的安全
 * {@link LogPipeline}；两者工作量存在实质差异，因此结果只用于定位新增安全语义的成本，不能解释为
 * 严格可比的性能回退或改善比例。</p>
 *
 * <p>状态绑定到单个 benchmark 线程，不用于证明并发吞吐、生产容量、端到端 IO 或长稳能力。</p>
 *
 * @author zn
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ProductionNetworkObserverBenchmark {

    /** 固定采样时间，避免把取系统时钟的开销混入 observer 基线。 */
    private static final Instant OBSERVATION_TIME = Instant.parse("2026-08-04T00:00:00Z");

    /**
     * 基准场景；由 JMH 注入，只允许成功鉴权或触发限流两种受控值。
     */
    @Param({"AUTH_SUCCEEDED", "RATE_LIMITED"})
    public String scenario;

    /** 真实内存指标注册表；仅由当前 benchmark 线程访问。 */
    private InMemoryMetricRegistry metricRegistry;

    /** 真实生产网络遥测 observer；仅由当前 benchmark 线程访问。 */
    private ProductionNetworkTelemetryObserver observer;

    /** 有界终端日志 sink；始终只保留最后一条日志引用。 */
    private LastRecordLogSink terminalSink;

    /** 当前参数对应的不可变生命周期观测事件。 */
    private ConnectionLifecycleObservation observation;

    /**
     * 为单个 JMH trial 创建真实 observer 依赖和固定输入。
     *
     * <p>该方法只修改当前 benchmark 线程私有的基准状态，不创建线程、端口或外部连接。</p>
     *
     * @throws IllegalArgumentException 当 JMH 注入未支持的场景名时抛出。
     */
    @Setup(Level.Trial)
    public void setUp() {
        metricRegistry = new InMemoryMetricRegistry();
        terminalSink = new LastRecordLogSink();
        LogPipeline logPipeline = new LogPipeline(List.of(), terminalSink);
        observer = new ProductionNetworkTelemetryObserver(logPipeline, metricRegistry);
        observation = createObservation(scenario);
    }

    /**
     * 同步执行一次生产网络生命周期观察。
     *
     * @return 本次管线最终写入的日志记录；不可为空；不可变；仅供 JMH 消费结果。
     * @throws NullPointerException 当 observer 未产生日志记录时由结果读取保护抛出。
     */
    @Benchmark
    public ZeroLogRecord observe() {
        observer.onEvent(observation);
        return terminalSink.lastRecord();
    }

    /**
     * 在每次 benchmark 调用后清空指标样本，保留已注册定义。
     *
     * <p>该方法修改当前线程私有注册表，线程安全性由注册表保证；清理开销不进入被测方法计时。</p>
     */
    @TearDown(Level.Invocation)
    public void clearSamples() {
        metricRegistry.clearSamples();
    }

    /**
     * 根据受控场景创建固定的生命周期观测事件。
     *
     * @param currentScenario JMH 场景名；不可为空。
     * @return 不可变、非空、线程安全的观测事件。
     * @throws IllegalArgumentException 当场景名不受支持时抛出。
     */
    private ConnectionLifecycleObservation createObservation(final String currentScenario) {
        return switch (Objects.requireNonNull(currentScenario, "currentScenario")) {
            case "AUTH_SUCCEEDED" -> authSucceededObservation();
            case "RATE_LIMITED" -> rateLimitedObservation();
            default -> throw new IllegalArgumentException("unsupported scenario: " + currentScenario);
        };
    }

    /**
     * 创建鉴权成功观测事件。
     *
     * @return 不可变、非空、线程安全的鉴权成功事件。
     */
    private ConnectionLifecycleObservation authSucceededObservation() {
        return new ConnectionLifecycleObservation(
                OBSERVATION_TIME,
                "game-gateway",
                "tcp",
                "benchmark-trace-auth",
                "benchmark-connection-auth",
                "192.0.2.0:*",
                ConnectionLifecycleState.ESTABLISHED,
                ConnectionLifecycleEventType.AUTH_SUCCEEDED,
                ConnectionLifecycleResult.SUCCEEDED,
                ConnectionRejectionReason.NONE,
                NetworkRateLimitScope.NONE,
                null,
                1_000_000L,
                0);
    }

    /**
     * 创建协议帧限流观测事件。
     *
     * @return 不可变、非空、线程安全的限流事件。
     */
    private ConnectionLifecycleObservation rateLimitedObservation() {
        return new ConnectionLifecycleObservation(
                OBSERVATION_TIME,
                "game-gateway",
                "tcp",
                "benchmark-trace-rate-limited",
                "benchmark-connection-rate-limited",
                "192.0.2.0:*",
                ConnectionLifecycleState.ESTABLISHED,
                ConnectionLifecycleEventType.RATE_LIMIT_EXCEEDED,
                ConnectionLifecycleResult.REJECTED,
                ConnectionRejectionReason.FRAME_RATE_LIMITED,
                NetworkRateLimitScope.FRAME,
                NetErrorCode.RATE_LIMITED,
                0L,
                1);
    }

}
