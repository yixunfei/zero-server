package group.zn.zero.runtime.bootstrap;

/**
 * starter 运行时配置键。
 *
 * @author zn
 */
public final class ZeroRuntimeConfigKeys {

    /**
     * 运行模式。
     */
    public static final String ZERO_MODE = "zero.mode";

    /**
     * 应用名称。
     */
    public static final String ZERO_NAME = "zero.name";

    /**
     * 默认本地运行模式。
     */
    public static final String MODE_LOCAL = "local";

    /**
     * 默认应用名称。
     */
    public static final String DEFAULT_NAME = "zeroServer";

    /**
     * 是否显式启用本地 CSV 配置热重载服务。
     */
    public static final String CONFIG_HOT_RELOAD_ENABLED = "zero.config.hot-reload.enabled";

    /**
     * 是否启用本地 CSV 文件 WatchService 自动监听。
     */
    public static final String CONFIG_HOT_RELOAD_WATCH_ENABLED = "zero.config.hot-reload.watch-enabled";

    /**
     * 本地 CSV 文件事件静默去抖毫秒数。
     */
    public static final String CONFIG_HOT_RELOAD_DEBOUNCE_MILLIS = "zero.config.hot-reload.debounce-ms";

    /**
     * 本地 CSV watcher 停止等待毫秒数。
     */
    public static final String CONFIG_HOT_RELOAD_STOP_TIMEOUT_MILLIS =
            "zero.config.hot-reload.stop-timeout-ms";

    /**
     * 初始加载和本地 watcher 自动重载审计操作者。
     */
    public static final String CONFIG_HOT_RELOAD_OPERATOR = "zero.config.hot-reload.operator";

    /**
     * 默认文件事件静默去抖毫秒数。
     */
    public static final long DEFAULT_CONFIG_HOT_RELOAD_DEBOUNCE_MILLIS = 500L;

    /**
     * 默认 watcher 停止等待毫秒数。
     */
    public static final long DEFAULT_CONFIG_HOT_RELOAD_STOP_TIMEOUT_MILLIS = 3_000L;

    /**
     * 默认本地配置热重载审计操作者。
     */
    public static final String DEFAULT_CONFIG_HOT_RELOAD_OPERATOR = "local-config";

    /**
     * 是否显式启用本地受管定时任务运行时。
     */
    public static final String SCHEDULER_ENABLED = "zero.scheduler.enabled";

    /**
     * 本地受管定时任务最大活动 handle 数量。
     */
    public static final String SCHEDULER_MAX_TASKS = "zero.scheduler.max-tasks";

    /**
     * 本地受管定时任务最大已提交或异步未完成执行数量。
     */
    public static final String SCHEDULER_MAX_IN_FLIGHT = "zero.scheduler.max-in-flight";

    /**
     * 本地受管定时任务停止等待毫秒数。
     */
    public static final String SCHEDULER_STOP_TIMEOUT_MILLIS = "zero.scheduler.stop-timeout-ms";

    /**
     * 本地受管定时任务 timer 线程名前缀。
     */
    public static final String SCHEDULER_THREAD_NAME_PREFIX = "zero.scheduler.thread-name-prefix";

    /**
     * 默认活动任务上限。
     */
    public static final int DEFAULT_SCHEDULER_MAX_TASKS = 1024;

    /**
     * 默认在途执行上限。
     */
    public static final int DEFAULT_SCHEDULER_MAX_IN_FLIGHT = 256;

    /**
     * 默认停止等待毫秒数。
     */
    public static final long DEFAULT_SCHEDULER_STOP_TIMEOUT_MILLIS = 3_000L;

    /**
     * 默认 timer 线程名前缀。
     */
    public static final String DEFAULT_SCHEDULER_THREAD_NAME_PREFIX = "zero-scheduler";

    private ZeroRuntimeConfigKeys() {
    }
}
