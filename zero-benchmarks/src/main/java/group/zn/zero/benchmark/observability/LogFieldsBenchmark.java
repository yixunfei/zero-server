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
import java.util.Collections;
import java.util.LinkedHashMap;
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
 * 结构化日志字段数量的创建与安全管线开销基准。
 *
 * <p>每次调用从固定字段输入创建真实 {@link ZeroLogRecord}，再同步写入真实
 * {@link LogPipeline}。终端只保留最后一条记录，并在 invocation teardown 中清理；
 * 基准不启动线程、不执行外部 IO，也不用于推断并发吞吐、尾延迟或生产容量。
 *
 * @author zn
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class LogFieldsBenchmark {

    /**
     * 固定日志时间，避免把系统时钟读取计入测量。
     */
    private static final Instant LOG_TIME = Instant.parse("2026-08-04T00:00:00Z");

    /**
     * 固定日志来源，不计入每次调用的值对象初始化。
     */
    private static final LogSource LOG_SOURCE =
            new LogSource("game-service", "benchmark-1", "zero-benchmarks");

    /**
     * 固定成功操作，不携带 ErrorCode。
     */
    private static final LogOperation LOG_OPERATION =
            new LogOperation("benchmark.log.fields", LogResult.SUCCESS, null);

    /**
     * 扩展字段数量；由 JMH 注入，只允许日志预算内的三个受控值。
     */
    @Param({"0", "8", "32"})
    public int fieldCount;

    /**
     * 固定、不可变的字段输入；仅由当前 benchmark 线程读取。
     */
    private Map<String, String> fields;

    /**
     * 真实日志安全写入入口；仅由当前 benchmark 线程访问。
     */
    private LogAppender logAppender;

    /**
     * 有界日志终端；仅保留本次调用的最后一条记录。
     */
    private LastRecordLogSink terminalSink;

    /**
     * 为单个 JMH trial 创建固定输入和真实日志管线。
     *
     * <p>该方法只修改当前 benchmark 线程私有状态，不创建线程、不执行外部 IO。
     *
     * @throws IllegalArgumentException JMH 注入未声明的字段数量时抛出。
     */
    @Setup(Level.Trial)
    public void setUp() {
        fields = createFields(fieldCount);
        terminalSink = new LastRecordLogSink();
        logAppender = new LogPipeline(List.of(), terminalSink);
    }

    /**
     * 创建并安全写入一条具有受控字段数量的日志记录。
     *
     * <p>该方法会创建不可变日志记录并覆盖线程私有终端的旧引用，不修改固定字段输入。
     *
     * @return 真实管线最终写入的不可变日志记录；不可为空且线程安全。
     */
    @Benchmark
    public ZeroLogRecord append() {
        ZeroLogRecord record = ZeroLogRecord.create(
                LOG_TIME,
                LogLevel.INFO,
                LogType.BUSINESS,
                LOG_SOURCE,
                LOG_OPERATION,
                "benchmark-trace-log-fields",
                "benchmark structured log fields",
                fields);
        logAppender.append(record);
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

    /**
     * 创建受控数量的固定安全字段。
     *
     * @param count 字段数量；只允许 0、8 或 32。
     * @return 不可变、有序、可能为空、线程安全的字段 Map；零字段时为空。
     * @throws IllegalArgumentException 字段数量不受支持时抛出。
     */
    private Map<String, String> createFields(final int count) {
        if (count != 0 && count != 8 && count != 32) {
            throw new IllegalArgumentException("unsupported fieldCount: " + count);
        }
        if (count == 0) {
            return Map.of();
        }
        Map<String, String> created = new LinkedHashMap<>(count);
        for (int index = 0; index < count; index++) {
            created.put("dimension" + index, "benchmark-value-" + index);
        }
        return Collections.unmodifiableMap(created);
    }
}
