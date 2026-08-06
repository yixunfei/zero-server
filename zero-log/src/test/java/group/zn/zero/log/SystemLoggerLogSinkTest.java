package group.zn.zero.log;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Map;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

/**
 * 系统日志 sink 与稳定单行格式测试。
 *
 * @author zn
 */
class SystemLoggerLogSinkTest {

    /**
     * 验证格式化器按冻结顺序输出固定字段、已排序扩展字段和单行控制字符。
     */
    @Test
    void formatterShouldRenderStableSingleLine() {
        ZeroLogRecord record = ZeroLogRecord.create(
                Instant.parse("2026-05-25T00:00:00Z"),
                LogLevel.INFO,
                LogType.RUNTIME,
                new LogSource("game-service", "local-1", "zero-log"),
                new LogOperation("runtime.start", LogResult.SUCCESS, null),
                "trace-system-logger",
                "started\nlocally\u0000",
                Map.of("z", "2\r", "a", "1\t"));

        String line = LogRecordFormatter.formatLine(record);

        assertEquals(
                "schemaVersion=1 time=2026-05-25T00:00:00Z level=INFO logType=RUNTIME "
                        + "serviceName=game-service instanceId=local-1 module=zero-log "
                        + "operation=runtime.start result=SUCCESS traceId=trace-system-logger "
                        + "errorCode= message=started\\nlocally\\u0000 fields=a=1\\t,z=2\\r",
                line);
    }

    /**
     * 验证显式 LogLevel 是 System.Logger 等级的唯一来源。
     */
    @Test
    void sinkShouldMapEveryExplicitLogLevel() {
        CapturingLogger logger = new CapturingLogger();
        SystemLoggerLogSink sink = new SystemLoggerLogSink(logger);

        assertLevel(sink, logger, LogLevel.TRACE, System.Logger.Level.TRACE);
        assertLevel(sink, logger, LogLevel.DEBUG, System.Logger.Level.DEBUG);
        assertLevel(sink, logger, LogLevel.INFO, System.Logger.Level.INFO);
        assertLevel(sink, logger, LogLevel.WARN, System.Logger.Level.WARNING);
        assertLevel(sink, logger, LogLevel.ERROR, System.Logger.Level.ERROR);
    }

    /**
     * 断言显式日志等级映射结果。
     *
     * @param sink 系统日志 sink；不可为空。
     * @param logger 捕获 logger；不可为空。
     * @param logLevel zeroServer 日志等级；不可为空。
     * @param expected JDK 日志等级；不可为空。
     */
    private void assertLevel(
            final SystemLoggerLogSink sink,
            final CapturingLogger logger,
            final LogLevel logLevel,
            final System.Logger.Level expected) {
        sink.append(ZeroLogRecord.create(
                Instant.EPOCH,
                logLevel,
                LogType.RUNTIME,
                new LogSource("game-service", "local-1", "zero-log"),
                new LogOperation(
                        "level.test",
                        logLevel == LogLevel.ERROR ? LogResult.FAILURE : LogResult.SUCCESS,
                        logLevel == LogLevel.ERROR ? LogErrorCode.SINK_FAILED : null),
                "trace-level",
                "level test",
                Map.of()));
        assertEquals(expected, logger.level);
    }

    /**
     * 捕获 JDK 日志调用的测试 logger。
     */
    private static final class CapturingLogger implements System.Logger {

        /**
         * 最后一次日志等级。
         */
        private Level level;

        /**
         * 返回日志器名称。
         *
         * @return 日志器名称；不可为空。
         */
        @Override
        public String getName() {
            return "capturing";
        }

        /**
         * 判断日志等级是否启用。
         *
         * @param currentLevel 日志等级；不可为空。
         * @return 始终为 true；线程不安全，仅供单线程测试使用。
         */
        @Override
        public boolean isLoggable(final Level currentLevel) {
            return true;
        }

        /**
         * 捕获普通日志调用。
         *
         * @param currentLevel 日志等级；不可为空。
         * @param bundle 资源包；可为空。
         * @param msg 日志文本；可为空。
         * @param thrown 异常；可为空。
         */
        @Override
        public void log(
                final Level currentLevel,
                final ResourceBundle bundle,
                final String msg,
                final Throwable thrown) {
            this.level = currentLevel;
        }

        /**
         * 捕获参数化日志调用。
         *
         * @param currentLevel 日志等级；不可为空。
         * @param bundle 资源包；可为空。
         * @param format 日志模板；可为空。
         * @param params 日志参数；可为空。
         */
        @Override
        public void log(
                final Level currentLevel,
                final ResourceBundle bundle,
                final String format,
                final Object... params) {
            this.level = currentLevel;
        }
    }
}
