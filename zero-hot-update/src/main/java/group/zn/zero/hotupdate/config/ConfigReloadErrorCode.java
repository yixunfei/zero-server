package group.zn.zero.hotupdate.config;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ErrorCode;

/**
 * 配置表加载与热重载错误码。
 *
 * @author zn
 */
public enum ConfigReloadErrorCode implements ErrorCode {

    /**
     * 配置表定义非法。
     */
    INVALID_DEFINITION("ZERO-CONFIG-INVALID-DEFINITION", "config table definition is invalid"),

    /**
     * 配置热重载运行参数非法。
     */
    INVALID_OPTIONS("ZERO-CONFIG-INVALID-OPTIONS", "config reload options are invalid"),

    /**
     * 配置表重复注册。
     */
    TABLE_ALREADY_REGISTERED("ZERO-CONFIG-TABLE-ALREADY-REGISTERED", "config table is already registered"),

    /**
     * 配置表未注册。
     */
    TABLE_NOT_FOUND("ZERO-CONFIG-TABLE-NOT-FOUND", "config table is not registered"),

    /**
     * 配置表尚未加载。
     */
    TABLE_NOT_LOADED("ZERO-CONFIG-TABLE-NOT-LOADED", "config table is not loaded"),

    /**
     * 配置表文件读取失败。
     */
    FILE_READ_FAILED("ZERO-CONFIG-FILE-READ-FAILED", "config table file read failed"),

    /**
     * CSV 语法解析失败。
     */
    CSV_PARSE_FAILED("ZERO-CONFIG-CSV-PARSE-FAILED", "config table csv parse failed"),

    /**
     * CSV 缺少必需列。
     */
    REQUIRED_COLUMN_MISSING("ZERO-CONFIG-REQUIRED-COLUMN-MISSING", "config table required column is missing"),

    /**
     * 配置表 key 为空。
     */
    EMPTY_KEY("ZERO-CONFIG-EMPTY-KEY", "config table key is empty"),

    /**
     * 配置表 key 重复。
     */
    DUPLICATE_KEY("ZERO-CONFIG-DUPLICATE-KEY", "config table key is duplicated"),

    /**
     * 配置表 key 转换失败。
     */
    KEY_DECODE_FAILED("ZERO-CONFIG-KEY-DECODE-FAILED", "config table key decode failed"),

    /**
     * 配置表行转换失败。
     */
    ROW_DECODE_FAILED("ZERO-CONFIG-ROW-DECODE-FAILED", "config table row decode failed"),

    /**
     * 配置表业务校验失败。
     */
    VALIDATION_FAILED("ZERO-CONFIG-VALIDATION-FAILED", "config table validation failed"),

    /**
     * 配置热重载服务状态非法。
     */
    INVALID_SERVICE_STATE("ZERO-CONFIG-INVALID-SERVICE-STATE", "config reload service state is invalid"),

    /**
     * 配置热重载服务未注册任何表。
     */
    NO_TABLES_REGISTERED("ZERO-CONFIG-NO-TABLES-REGISTERED", "no config tables are registered"),

    /**
     * 配置表初始加载失败。
     */
    INITIAL_LOAD_FAILED("ZERO-CONFIG-INITIAL-LOAD-FAILED", "config table initial load failed"),

    /**
     * 配置表运行中重载被拒绝。
     */
    RELOAD_REJECTED("ZERO-CONFIG-RELOAD-REJECTED", "config table reload was rejected"),

    /**
     * IO 执行器拒绝配置任务。
     */
    EXECUTOR_REJECTED("ZERO-CONFIG-EXECUTOR-REJECTED", "config reload executor rejected task"),

    /**
     * 本地文件 watcher 启动失败。
     */
    WATCHER_START_FAILED("ZERO-CONFIG-WATCHER-START-FAILED", "config file watcher start failed"),

    /**
     * 本地文件 watcher 运行失败。
     */
    WATCHER_FAILED("ZERO-CONFIG-WATCHER-FAILED", "config file watcher failed"),

    /**
     * 本地文件 watcher 停止失败。
     */
    WATCHER_STOP_FAILED("ZERO-CONFIG-WATCHER-STOP-FAILED", "config file watcher stop failed"),

    /**
     * 配置重载 observer 执行失败。
     */
    OBSERVER_FAILED("ZERO-CONFIG-OBSERVER-FAILED", "config reload observer failed");

    /**
     * 对外稳定错误码。
     */
    private final String code;

    /**
     * 默认错误说明。
     */
    private final String message;

    ConfigReloadErrorCode(final String code, final String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 返回错误分类。
     *
     * @return 热更错误分类；不可为空；线程安全。
     */
    @Override
    public ErrorCategory category() {
        return ErrorCategory.HOT_UPDATE;
    }

    /**
     * 返回对外稳定错误码。
     *
     * @return 错误码；不可为空；线程安全。
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * 返回默认错误说明。
     *
     * @return 默认说明；不可为空；线程安全。
     */
    @Override
    public String message() {
        return message;
    }
}
