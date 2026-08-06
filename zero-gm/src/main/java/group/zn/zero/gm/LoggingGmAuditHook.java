package group.zn.zero.gm;

import group.zn.zero.log.LogAppender;
import java.util.Objects;

/**
 * 通过统一安全日志管线落地 GM 审计事件的 hook。
 *
 * <p>本类型只依赖业务写入端口 {@link LogAppender}，不能绕过 pipeline 直接注入终端 LogSink。
 * append 失败会原样向执行器抛出，由执行器包装为携带 phase 与提交状态的
 * {@link GmAuditFailureException}，不会递归记录自身失败。</p>
 *
 * @author zn
 */
public final class LoggingGmAuditHook implements GmAuditHook {

    /** 统一安全日志写入端口。 */
    private final LogAppender logAppender;

    /** GM 审计记录工厂。 */
    private final GmAuditRecordFactory recordFactory;

    /**
     * 创建 logging GM 审计 hook。
     *
     * @param logAppender 标准安全日志写入端口；不可为空。
     * @param recordFactory 安全事件到统一日志记录的转换工厂；不可为空。
     * @throws NullPointerException 当日志写入端口或记录工厂为空时抛出。
     */
    public LoggingGmAuditHook(
            final LogAppender logAppender,
            final GmAuditRecordFactory recordFactory) {
        this.logAppender = Objects.requireNonNull(logAppender, "logAppender");
        this.recordFactory = Objects.requireNonNull(recordFactory, "recordFactory");
    }

    /**
     * 同步转换并写入一条安全 GM 审计记录。
     *
     * <p>本方法不修改事件；会同步调用 LogAppender，并把转换或落地异常向上抛出。
     * 线程安全性取决于注入的 LogAppender 实现，工厂本身不可变且线程安全。</p>
     *
     * @param event 已安全化的 GM 审计事件；不可为空。
     * @throws NullPointerException 当事件为空时抛出。
     * @throws RuntimeException 当记录转换、日志校验、processor 或 sink 失败时向上抛出。
     */
    @Override
    public void record(final GmAuditEvent event) {
        logAppender.append(recordFactory.create(Objects.requireNonNull(event, "event")));
    }
}
