package group.zn.zero.log;

import java.util.Objects;

/**
 * 基于 JDK {@link System.Logger} 的日志 sink。
 *
 * <p>该实现用于 starter、本地原型和无外部日志组件的 smoke flow。生产环境如果需要文件滚动、
 * Kafka 落地、批量写入或背压控制，应替换为专用 `LogSink` 实现。
 *
 * @author zn
 */
public final class SystemLoggerLogSink implements LogSink {

    /**
     * JDK 日志器。
     */
    private final System.Logger logger;

    /**
     * 创建系统日志 sink。
     *
     * @param logger JDK 日志器；不可为空。
     * @throws NullPointerException 当日志器为空时抛出。
     */
    public SystemLoggerLogSink(final System.Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * 按名称创建系统日志 sink。
     *
     * @param name 日志器名称；不可为空。
     * @return 系统日志 sink；不可为空；线程安全性由 JDK 日志器保证。
     * @throws NullPointerException 当名称为空时抛出。
     */
    public static SystemLoggerLogSink named(final String name) {
        return new SystemLoggerLogSink(System.getLogger(Objects.requireNonNull(name, "name")));
    }

    /**
     * 写入日志记录。
     *
     * @param record 日志记录；不可为空。
     * @throws NullPointerException 当日志记录为空时抛出。
     */
    @Override
    public void append(final ZeroLogRecord record) {
        ZeroLogRecord current = Objects.requireNonNull(record, "record");
        logger.log(levelOf(current.level()), LogRecordFormatter.formatLine(current));
    }

    /**
     * 转换 zeroServer 显式日志等级到 JDK 日志等级。
     *
     * @param level 日志等级；不可为空。
     * @return JDK 日志等级；不可为空。
     */
    private System.Logger.Level levelOf(final LogLevel level) {
        return switch (Objects.requireNonNull(level, "level")) {
            case TRACE -> System.Logger.Level.TRACE;
            case DEBUG -> System.Logger.Level.DEBUG;
            case INFO -> System.Logger.Level.INFO;
            case WARN -> System.Logger.Level.WARNING;
            case ERROR -> System.Logger.Level.ERROR;
        };
    }
}
