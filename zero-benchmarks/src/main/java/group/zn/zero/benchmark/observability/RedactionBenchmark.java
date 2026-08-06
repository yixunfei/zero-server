package group.zn.zero.benchmark.observability;

import group.zn.zero.log.LogAppender;
import group.zn.zero.log.LogLevel;
import group.zn.zero.log.LogOperation;
import group.zn.zero.log.LogPipeline;
import group.zn.zero.log.LogResult;
import group.zn.zero.log.LogSource;
import group.zn.zero.log.LogType;
import group.zn.zero.log.ZeroLogRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * 默认日志标识脱敏未命中与命中路径的安全管线基准。
 *
 * <p>{@code MISS} 使用普通安全字段并允许管线复用原记录；{@code HIT} 使用 operator 字段并
 * 触发框架固定文本脱敏。两种场景都复用固定输入，写入真实 {@link LogPipeline} 和有界终端，
 * 不启动线程、不执行外部 IO，也不包含 HMAC 或远程 sink 成本。
 *
 * @author zn
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class RedactionBenchmark {

    /**
     * 固定日志时间。
     */
    private static final Instant LOG_TIME = Instant.parse("2026-08-04T00:00:00Z");

    /**
     * 固定日志来源。
     */
    private static final LogSource LOG_SOURCE =
            new LogSource("game-service", "benchmark-1", "zero-benchmarks");

    /**
     * 固定成功操作。
     */
    private static final LogOperation LOG_OPERATION =
            new LogOperation("benchmark.log.redaction", LogResult.SUCCESS, null);

    /**
     * 脱敏场景；由 JMH 注入，只允许未命中或默认标识命中。
     */
    @Param({"MISS", "HIT"})
    public String redactionCase;

    /**
     * 当前场景的固定不可变日志输入。
     */
    private ZeroLogRecord inputRecord;

    /**
     * 真实日志安全写入入口；仅由当前 benchmark 线程访问。
     */
    private LogAppender logAppender;

    /**
     * 有界日志终端；只保留最后一条脱敏结果。
     */
    private LastRecordLogSink terminalSink;

    /**
     * 为单个 JMH trial 创建固定场景输入和真实日志管线。
     *
     * <p>该方法只修改当前 benchmark 线程私有状态，不创建线程、不执行外部 IO。
     *
     * @throws IllegalArgumentException JMH 注入未声明的脱敏场景时抛出。
     */
    @Setup(Level.Trial)
    public void setUp() {
        Map<String, String> fields = switch (redactionCase) {
            case "MISS" -> Map.of("safeDimension", "benchmark-value");
            case "HIT" -> Map.of("operator", "benchmark-operator");
            default -> throw new IllegalArgumentException("unsupported redactionCase: " + redactionCase);
        };
        inputRecord = ZeroLogRecord.create(
                LOG_TIME,
                LogLevel.INFO,
                LogType.SECURITY,
                LOG_SOURCE,
                LOG_OPERATION,
                "benchmark-trace-redaction",
                "benchmark log redaction",
                fields);
        terminalSink = new LastRecordLogSink();
        logAppender = new LogPipeline(List.of(), terminalSink);
    }

    /**
     * 把固定记录写入真实安全管线并返回最终记录。
     *
     * <p>该方法会覆盖线程私有终端的旧引用，不修改固定输入记录。
     *
     * @return 管线最终写入的不可变日志记录；不可为空且线程安全。
     */
    @Benchmark
    public ZeroLogRecord append() {
        logAppender.append(inputRecord);
        return terminalSink.lastRecord();
    }

    /**
     * 在每次 benchmark 调用后释放终端持有的日志引用。
     *
     * <p>清理发生在计时范围外，只修改当前线程私有终端状态。
     */
    @TearDown(Level.Invocation)
    public void clearTerminal() {
        terminalSink.clear();
    }
}
