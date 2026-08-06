package group.zn.zero.starter;

import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.hotupdate.config.ConfigHotReloadOptions;
import group.zn.zero.hotupdate.config.ConfigReloadErrorCode;
import group.zn.zero.hotupdate.config.ConfigReloadRequestFactory;
import group.zn.zero.hotupdate.config.LocalConfigHotReloadService;
import group.zn.zero.hotupdate.config.LoggingConfigReloadObserver;
import group.zn.zero.log.LogAppender;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Starter 本地 CSV 配置热重载显式装配工厂。
 *
 * <p>该工厂只在 {@code zero.config.hot-reload.enabled=true} 时创建服务，并把服务追加到
 * {@link ZeroRuntimeBuilder} 生命周期。所有 CSV 文件 IO 和可选 WatchService 都复用 starter
 * 管理的非内联 remote IO executor；本工厂不创建线程池，也不注册业务配置表。
 *
 * @author zn
 */
public final class ZeroConfigHotReloadFactory {

    private ZeroConfigHotReloadFactory() {
    }

    /**
     * 判断本地 CSV 配置热重载是否显式启用。
     *
     * <p>该方法只读取配置，不修改运行时状态。布尔值只接受忽略大小写的 {@code true/false}，
     * 避免拼写错误被静默解释为关闭。
     *
     * @param config 统一配置；不可为空。
     * @return true 表示显式启用；线程安全性由配置实现声明。
     * @throws ZeroException 当布尔配置值非法时抛出并绑定配置热更 ErrorCode。
     */
    public static boolean enabled(final ZeroConfig config) {
        return ZeroStarterConfigParser.strictBoolean(
                Objects.requireNonNull(config, "config"),
                ZeroRuntimeConfigKeys.CONFIG_HOT_RELOAD_ENABLED,
                false,
                ConfigReloadErrorCode.INVALID_OPTIONS);
    }

    /**
     * 按 Starter 配置创建并挂载本地 CSV 配置热重载服务。
     *
     * <p>未启用时返回空且不修改 builder；启用时创建未启动服务并追加一个生命周期组件。
     * 调用方必须在 runtime 启动前通过返回的服务注册全部表。该方法非线程安全，只应在单线程
     * 装配阶段调用；不会启动线程、读取 CSV 或修改游戏业务数据。
     *
     * @param builder 运行时装配构建器；不可为空，启用时会追加生命周期组件。
     * @param config 统一配置；不可为空。
     * @param logAppender 审计和错误日志安全写入端口；不可为空。
     * @param executors Starter 受管执行器；不可为空，remote IO executor 必须非内联。
     * @return 配置服务；未启用时为空，启用时非空；返回对象可安全并发读取配置表。
     * @throws ZeroException 当配置非法或 remote IO executor 可能内联时抛出。
     */
    public static Optional<LocalConfigHotReloadService> configure(
            final ZeroRuntimeBuilder builder,
            final ZeroConfig config,
            final LogAppender logAppender,
            final ZeroRuntimeExecutors executors) {
        ZeroRuntimeBuilder checkedBuilder = Objects.requireNonNull(builder, "builder");
        ZeroConfig checkedConfig = Objects.requireNonNull(config, "config");
        LogAppender checkedLogAppender = Objects.requireNonNull(logAppender, "logAppender");
        ZeroRuntimeExecutors checkedExecutors = Objects.requireNonNull(executors, "executors");
        if (!enabled(checkedConfig)) {
            return Optional.empty();
        }
        if (checkedExecutors.remoteIoMayInline()) {
            throw invalid("config reload requires a managed non-inline remote IO executor", null);
        }
        LocalConfigHotReloadService service = new LocalConfigHotReloadService(
                checkedExecutors.remoteIoExecutor(),
                ConfigReloadRequestFactory.local(operator(checkedConfig)),
                new LoggingConfigReloadObserver(checkedLogAppender),
                options(checkedConfig));
        checkedBuilder.addLifecycleComponent(service);
        return Optional.of(service);
    }

    private static ConfigHotReloadOptions options(final ZeroConfig config) {
        boolean watchEnabled = ZeroStarterConfigParser.strictBoolean(
                config,
                ZeroRuntimeConfigKeys.CONFIG_HOT_RELOAD_WATCH_ENABLED,
                false,
                ConfigReloadErrorCode.INVALID_OPTIONS);
        long debounceMillis = ZeroStarterConfigParser.positiveLong(
                config,
                ZeroRuntimeConfigKeys.CONFIG_HOT_RELOAD_DEBOUNCE_MILLIS,
                ZeroRuntimeConfigKeys.DEFAULT_CONFIG_HOT_RELOAD_DEBOUNCE_MILLIS,
                ConfigReloadErrorCode.INVALID_OPTIONS);
        long stopTimeoutMillis = ZeroStarterConfigParser.positiveLong(
                config,
                ZeroRuntimeConfigKeys.CONFIG_HOT_RELOAD_STOP_TIMEOUT_MILLIS,
                ZeroRuntimeConfigKeys.DEFAULT_CONFIG_HOT_RELOAD_STOP_TIMEOUT_MILLIS,
                ConfigReloadErrorCode.INVALID_OPTIONS);
        return new ConfigHotReloadOptions(
                watchEnabled,
                Duration.ofMillis(debounceMillis),
                Duration.ofMillis(stopTimeoutMillis));
    }

    private static String operator(final ZeroConfig config) {
        return ZeroStarterConfigParser.nonBlank(
                config,
                ZeroRuntimeConfigKeys.CONFIG_HOT_RELOAD_OPERATOR,
                ZeroRuntimeConfigKeys.DEFAULT_CONFIG_HOT_RELOAD_OPERATOR,
                ConfigReloadErrorCode.INVALID_OPTIONS);
    }

    private static ZeroException invalid(final String message, final Throwable cause) {
        return ZeroException.of(ConfigReloadErrorCode.INVALID_OPTIONS, message, cause);
    }
}
