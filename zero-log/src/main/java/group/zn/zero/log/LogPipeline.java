package group.zn.zero.log;

import group.zn.zero.core.error.ZeroException;
import java.util.List;
import java.util.Objects;

/**
 * 统一日志处理与安全落地管线。
 *
 * <pre>
 * append(record)
 *   -> 结构、预算和 ErrorCode 校验
 *   -> 首次不可关闭安全门
 *   -> 有 processor 时按顺序执行并复验结构
 *   -> 有 processor 时执行 sink 前不可关闭终端安全门
 *   -> 无 processor 时首次安全门同时承担终端门职责
 *   -> 终端 LogSink
 * </pre>
 *
 * <p>管线不创建线程池、不读取线程上下文、不执行隐式异步；线程安全性取决于 sink、processor、
 * 附加策略和 redactor 实现。正式业务调用点应只持有 {@link LogAppender}。
 *
 * @author zn
 */
public final class LogPipeline implements LogAppender {

    /**
     * 日志处理器有序快照。
     */
    private final List<LogProcessor> processors;

    /**
     * 终端装配 sink。
     */
    private final LogSink sink;

    /**
     * 不可关闭的安全门。
     */
    private final LogSafetyGate safetyGate;

    /**
     * 使用固定脱敏和无附加限制创建日志管线。
     *
     * @param processors 处理器列表；不可为空；按列表顺序执行并防御性复制一次。
     * @param sink 终端落地 SPI；不可为空。
     * @throws NullPointerException 列表、任一处理器或 sink 为空时抛出。
     */
    public LogPipeline(final List<LogProcessor> processors, final LogSink sink) {
        this(processors, sink, SensitiveFieldPolicy.allowAll(), FixedLogIdentifierRedactor.instance());
    }

    /**
     * 使用附加收紧策略和指定标识脱敏器创建日志管线。
     *
     * <p>附加策略无法替换或放宽框架默认安全策略。所有传入协作者必须线程安全，或仅在单线程
     * 管线中使用；管线不为它们增加锁。
     *
     * @param processors 处理器列表；不可为空；按列表顺序执行并防御性复制一次。
     * @param sink 终端落地 SPI；不可为空。
     * @param additionalPolicy 附加收紧策略；不可为空。
     * @param redactor 框架固定脱敏或 HMAC-SHA-256 标识脱敏器；不可为空。
     * @throws NullPointerException 任一参数或处理器为空时抛出。
     */
    public LogPipeline(
            final List<LogProcessor> processors,
            final LogSink sink,
            final SensitiveFieldPolicy additionalPolicy,
            final LogIdentifierRedactor redactor) {
        this.processors = List.copyOf(Objects.requireNonNull(processors, "processors"));
        this.sink = Objects.requireNonNull(sink, "sink");
        this.safetyGate = new LogSafetyGate(additionalPolicy, redactor);
    }

    /**
     * 同步安全写入日志记录。
     *
     * <p>该方法不会修改原记录。无 processor、脱敏或控制字符变化时，终端 sink 收到同一对象引用。
     * 处理器和 sink 失败被包装后直接向上抛出，不会通过当前管线递归记录自身失败。
     *
     * @param record 日志记录；不可为空。
     * @throws ZeroException 记录非法、敏感内容被拒绝、处理器失败或 sink 失败时抛出。
     */
    @Override
    public void append(final ZeroLogRecord record) {
        LogRecordValidator.requireValid(record);
        if (processors.isEmpty()) {
            appendToSink(safetyGate.sanitizeTerminal(record));
            return;
        }
        LogSafetyGate.SanitizationResult initial = safetyGate.sanitizeBeforeProcessors(record);
        ZeroLogRecord current = initial.record();
        for (LogProcessor processor : processors) {
            current = process(processor, current);
        }
        current = safetyGate.sanitizeAfterProcessors(current, initial);
        appendToSink(current);
    }

    /**
     * 执行处理器并把异常、空值和非法记录统一包装。
     *
     * @param processor 处理器；不可为空。
     * @param record 当前记录；不可为空。
     * @return 处理后的有效记录；不可为空。
     * @throws ZeroException 处理器失败或返回非法记录时抛出。
     */
    private ZeroLogRecord process(final LogProcessor processor, final ZeroLogRecord record) {
        try {
            ZeroLogRecord processed = processor.process(record);
            if (processed == null) {
                throw new NullPointerException("processedRecord");
            }
            LogRecordValidator.requireValid(processed);
            return processed;
        } catch (RuntimeException exception) {
            throw ZeroException.of(
                    LogErrorCode.PROCESSOR_FAILED,
                    LogErrorCode.PROCESSOR_FAILED.message(),
                    exception);
        }
    }

    /**
     * 调用终端 sink 并统一包装失败。
     *
     * @param record 已通过终端安全门的记录；不可为空。
     * @throws ZeroException sink 写入失败时抛出并保留 cause。
     */
    private void appendToSink(final ZeroLogRecord record) {
        try {
            sink.append(record);
        } catch (RuntimeException exception) {
            throw ZeroException.of(LogErrorCode.SINK_FAILED, LogErrorCode.SINK_FAILED.message(), exception);
        }
    }
}
