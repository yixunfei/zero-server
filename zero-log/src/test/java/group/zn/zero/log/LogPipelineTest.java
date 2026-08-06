package group.zn.zero.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 日志管线结构、失败包装和类型隔离测试。
 *
 * @author zn
 */
class LogPipelineTest {

    /**
     * 验证处理器可以追加字段并经安全入口写入终端 sink。
     */
    @Test
    void pipelineShouldProcessRecordAndAppendToSink() {
        InMemoryLogSink sink = new InMemoryLogSink();
        LogPipeline pipeline = new LogPipeline(
                List.of(record -> record.withField("route", "memory")),
                sink);
        ZeroLogRecord input = successRecord(Map.of("eventId", "event-1"));

        pipeline.append(input);

        assertEquals(1, sink.records().size());
        assertEquals("memory", sink.records().getFirst().fields().get("route"));
        assertEquals("event-1", sink.records().getFirst().fields().get("eventId"));
    }

    /**
     * 验证无 processor、无脱敏和无控制字符变化时只扫描一次并复用原日志对象。
     */
    @Test
    void pipelineShouldReuseUnchangedRecord() {
        AtomicInteger policyEvaluations = new AtomicInteger();
        SensitiveFieldPolicy countingAllowPolicy = (path, value) -> {
            policyEvaluations.incrementAndGet();
            return SensitiveFieldAction.ALLOW;
        };
        InMemoryLogSink sink = new InMemoryLogSink();
        LogPipeline pipeline = new LogPipeline(
                List.of(),
                sink,
                countingAllowPolicy,
                FixedLogIdentifierRedactor.instance());
        ZeroLogRecord input = successRecord(Map.of("route", "memory"));

        pipeline.append(input);

        assertSame(input, sink.records().getFirst());
        assertEquals(2, policyEvaluations.get());
    }

    /**
     * 验证处理器异常被模块错误码包装并保留 cause。
     */
    @Test
    void processorFailureShouldBeWrappedWithoutRecursiveLogging() {
        IllegalStateException cause = new IllegalStateException("processor failed");
        InMemoryLogSink sink = new InMemoryLogSink();
        LogPipeline pipeline = new LogPipeline(List.of(record -> {
            throw cause;
        }), sink);

        ZeroException exception = assertThrows(ZeroException.class,
                () -> pipeline.append(successRecord(Map.of())));

        assertEquals(LogErrorCode.PROCESSOR_FAILED, exception.errorCode());
        assertSame(cause, exception.getCause());
        assertFalse(sink.records().iterator().hasNext());
    }

    /**
     * 验证处理器返回空记录时使用 PROCESSOR_FAILED，而不是向下游传播裸空指针。
     */
    @Test
    void nullProcessorResultShouldBeWrapped() {
        LogPipeline pipeline = new LogPipeline(List.of(record -> null), new InMemoryLogSink());

        ZeroException exception = assertThrows(ZeroException.class,
                () -> pipeline.append(successRecord(Map.of())));

        assertEquals(LogErrorCode.PROCESSOR_FAILED, exception.errorCode());
        assertEquals(NullPointerException.class, exception.getCause().getClass());
    }

    /**
     * 验证终端 sink 异常被模块错误码包装并保留 cause。
     */
    @Test
    void sinkFailureShouldBeWrappedWithoutCallingPipelineAgain() {
        IllegalStateException cause = new IllegalStateException("sink failed");
        LogSink sink = record -> {
            throw cause;
        };
        LogPipeline pipeline = new LogPipeline(List.of(), sink);

        ZeroException exception = assertThrows(ZeroException.class,
                () -> pipeline.append(successRecord(Map.of())));

        assertEquals(LogErrorCode.SINK_FAILED, exception.errorCode());
        assertSame(cause, exception.getCause());
    }

    /**
     * 验证业务安全入口与终端 SPI 在类型上隔离。
     */
    @Test
    void pipelineShouldExposeOnlyAppenderContract() {
        LogPipeline pipeline = new LogPipeline(List.of(), new InMemoryLogSink());

        assertSame(LogAppender.class, pipeline.getClass().getInterfaces()[0]);
        assertFalse(LogSink.class.isAssignableFrom(LogPipeline.class));
    }

    /**
     * 创建有效成功日志。
     *
     * @param fields 日志字段；不可为空。
     * @return 有效日志；不可为空；线程安全。
     */
    private ZeroLogRecord successRecord(final Map<String, String> fields) {
        return ZeroLogRecord.create(
                Instant.parse("2026-05-23T00:00:00Z"),
                LogLevel.INFO,
                LogType.BUSINESS,
                new LogSource("game-service", "local-1", "zero-test"),
                new LogOperation("flow.complete", LogResult.SUCCESS, null),
                "trace-log-1",
                "flow completed",
                fields);
    }
}
