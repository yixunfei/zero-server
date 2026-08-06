package group.zn.zero.hotupdate.config;

import java.time.Duration;
import java.util.Objects;

/**
 * 本地配置热重载运行参数。
 *
 * @param watchEnabled 是否启用本地 WatchService。
 * @param debounce 文件事件静默去抖时间；必须为正数。
 * @param stopTimeout watcher 停止等待时间；必须为正数。
 * @author zn
 */
public record ConfigHotReloadOptions(
        boolean watchEnabled,
        Duration debounce,
        Duration stopTimeout) {

    /**
     * 默认文件事件去抖时间。
     */
    public static final Duration DEFAULT_DEBOUNCE = Duration.ofMillis(500);

    /**
     * 默认 watcher 停止等待时间。
     */
    public static final Duration DEFAULT_STOP_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 运行参数标准化构造器。
     *
     * @throws NullPointerException 当时间参数为空时抛出。
     * @throws IllegalArgumentException 当时间参数不是正数时抛出。
     */
    public ConfigHotReloadOptions {
        debounce = requirePositive(debounce, "debounce");
        stopTimeout = requirePositive(stopTimeout, "stopTimeout");
    }

    /**
     * 创建仅支持显式 reload 的默认参数。
     *
     * @return 手工重载参数；不可为空；线程安全。
     */
    public static ConfigHotReloadOptions manualOnly() {
        return new ConfigHotReloadOptions(false, DEFAULT_DEBOUNCE, DEFAULT_STOP_TIMEOUT);
    }

    /**
     * 创建启用本地文件监听的默认参数。
     *
     * @return watcher 参数；不可为空；线程安全。
     */
    public static ConfigHotReloadOptions watchingDefaults() {
        return new ConfigHotReloadOptions(true, DEFAULT_DEBOUNCE, DEFAULT_STOP_TIMEOUT);
    }

    private static Duration requirePositive(final Duration value, final String name) {
        Duration checked = Objects.requireNonNull(value, name);
        if (checked.isZero() || checked.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return checked;
    }
}
