package group.zn.zero.monitor;

import java.util.Objects;

/**
 * JDK System.Logger 告警 sink。
 *
 * @author zn
 */
public final class SystemLoggerAlertSink implements AlertSink {

    /**
     * 日志器。
     */
    private final System.Logger logger;

    /**
     * 创建告警日志 sink。
     *
     * @param logger 日志器；不可为空。
     * @throws NullPointerException 当日志器为空时抛出。
     */
    public SystemLoggerAlertSink(final System.Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * 按名称创建告警日志 sink。
     *
     * @param name 日志名称；不可为空。
     * @return 告警日志 sink；不可为空；线程安全性由 JDK 日志器保证。
     */
    public static SystemLoggerAlertSink named(final String name) {
        return new SystemLoggerAlertSink(System.getLogger(Objects.requireNonNull(name, "name")));
    }

    /**
     * 写入告警事件。
     *
     * @param event 告警事件；不可为空。
     */
    @Override
    public void publish(final AlertEvent event) {
        AlertEvent current = Objects.requireNonNull(event, "event");
        logger.log(levelOf(current.severity()), format(current));
    }

    private System.Logger.Level levelOf(final AlertSeverity severity) {
        return switch (severity) {
            case INFO -> System.Logger.Level.INFO;
            case WARNING -> System.Logger.Level.WARNING;
            case CRITICAL -> System.Logger.Level.ERROR;
        };
    }

    private String format(final AlertEvent event) {
        return "alert rule=" + event.ruleName()
                + " severity=" + event.severity()
                + " metric=" + event.metricName()
                + " value=" + event.value()
                + " threshold=" + event.threshold()
                + " labels=" + event.labels()
                + " message=" + event.message();
    }
}
