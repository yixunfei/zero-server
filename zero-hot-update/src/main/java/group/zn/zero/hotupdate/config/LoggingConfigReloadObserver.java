package group.zn.zero.hotupdate.config;

import group.zn.zero.hotupdate.HotUpdateRequest;
import group.zn.zero.log.LogAppender;
import group.zn.zero.log.LogLevel;
import group.zn.zero.log.LogOperation;
import group.zn.zero.log.LogResult;
import group.zn.zero.log.LogSource;
import group.zn.zero.log.LogType;
import group.zn.zero.log.ZeroLogRecord;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 配置重载审计与错误日志观察者。
 *
 * <p>日志只包含低基数表名、脱敏文件名、版本、摘要、行数、耗时和请求上下文，
 * 不记录完整路径、CSV 行、token、密码或密钥。
 *
 * @author zn
 */
public final class LoggingConfigReloadObserver implements ConfigReloadObserver {

    /**
     * 日志模块名。
     */
    private static final String MODULE = "zero-hot-update";

    /**
     * 配置热更日志来源。
     */
    private static final LogSource LOG_SOURCE = new LogSource("zero-server", "runtime", MODULE);

    /**
     * 经过框架安全边界的日志写入端口。
     */
    private final LogAppender logAppender;

    /**
     * 创建配置重载日志观察者。
     *
     * @param logAppender 安全日志写入端口；不可为空；线程安全性由实现声明。
     */
    public LoggingConfigReloadObserver(final LogAppender logAppender) {
        this.logAppender = Objects.requireNonNull(logAppender, "logAppender");
    }

    /**
     * 写入审计日志，并在拒绝时追加绑定 ErrorCode 的错误日志。
     *
     * @param request 热更请求；不可为空。
     * @param result 重载结果；不可为空。
     * @throws RuntimeException 当日志安全校验、处理或终端写入失败时抛出；不会吞掉异常。
     */
    @Override
    public void onReload(final HotUpdateRequest request, final ConfigReloadResult result) {
        HotUpdateRequest checkedRequest = Objects.requireNonNull(request, "request");
        ConfigReloadResult checkedResult = Objects.requireNonNull(result, "result");
        Map<String, String> fields = fields(checkedRequest, checkedResult);
        LogResult logResult = checkedResult.accepted() ? LogResult.SUCCESS : LogResult.REJECTED;
        logAppender.append(ZeroLogRecord.create(
                checkedResult.completedAt(),
                checkedResult.accepted() ? LogLevel.INFO : LogLevel.WARN,
                LogType.AUDIT,
                LOG_SOURCE,
                new LogOperation("config-reload", logResult, checkedResult.errorCode()),
                checkedRequest.traceId(),
                "config table reload " + checkedResult.status().name().toLowerCase(java.util.Locale.ROOT),
                fields));
        if (checkedResult.status() == ConfigReloadStatus.REJECTED) {
            logAppender.append(ZeroLogRecord.create(
                    checkedResult.completedAt(),
                    LogLevel.ERROR,
                    LogType.ERROR,
                    LOG_SOURCE,
                    new LogOperation("config-reload", LogResult.REJECTED, checkedResult.errorCode()),
                    checkedRequest.traceId(),
                    "config table reload rejected",
                    fields));
        }
    }

    private Map<String, String> fields(
            final HotUpdateRequest request,
            final ConfigReloadResult result) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("table", result.tableName());
        fields.put("source", result.sourceName());
        fields.put("operator", request.operator());
        fields.put("requestedVersion", request.version());
        fields.put("previousVersion", Long.toString(result.previousVersion()));
        fields.put("currentVersion", Long.toString(result.currentVersion()));
        fields.put("checksum", result.checksum());
        fields.put("rowCount", Integer.toString(result.rowCount()));
        fields.put("result", result.status().name().toLowerCase(java.util.Locale.ROOT));
        fields.put("elapsedMillis", Long.toString(result.elapsed().toMillis()));
        return Map.copyOf(fields);
    }
}
