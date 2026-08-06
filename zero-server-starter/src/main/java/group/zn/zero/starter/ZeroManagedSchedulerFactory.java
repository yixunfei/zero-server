package group.zn.zero.starter;

import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.scheduler.SchedulerErrorCode;
import group.zn.zero.log.LogAppender;
import group.zn.zero.monitor.MonitorRuntime;
import group.zn.zero.starter.scheduler.LocalManagedScheduler;
import group.zn.zero.starter.scheduler.LocalManagedSchedulerOptions;
import group.zn.zero.starter.scheduler.LoggingScheduledTaskObserver;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Starter 本地受管定时任务显式装配工厂。
 *
 * <p>工厂只在 {@code zero.scheduler.enabled=true} 时创建 scheduler，并通过基础设施生命周期
 * 插入点保证其先于持久化和业务组件启动、后于它们停止。Timer 资源由 scheduler 生命周期拥有，
 * 用户任务和 observer 复用 Starter 受管的非内联 background executor；本工厂不创建业务线程池。</p>
 *
 * @author zn
 */
public final class ZeroManagedSchedulerFactory {

    private ZeroManagedSchedulerFactory() {
    }

    /**
     * 判断本地受管定时任务是否显式启用。
     *
     * <p>该方法只读取配置，不启动 timer、不修改 builder 或业务状态。布尔值只接受忽略大小写的
     * {@code true/false}，避免拼写错误被静默解释为关闭。</p>
     *
     * @param config 统一配置；不可为空。
     * @return true 表示显式启用；线程安全性由配置实现声明。
     * @throws ZeroException 当布尔配置值非法时抛出并绑定 Scheduler ErrorCode。
     */
    public static boolean enabled(final ZeroConfig config) {
        return ZeroStarterConfigParser.strictBoolean(
                Objects.requireNonNull(config, "config"),
                ZeroRuntimeConfigKeys.SCHEDULER_ENABLED,
                false,
                SchedulerErrorCode.INVALID_OPTIONS);
    }

    /**
     * 按 Starter 配置创建并挂载本地受管定时任务运行时。
     *
     * <p>未启用时返回空且不修改 builder、不注册指标、不创建 timer。启用时创建未启动 scheduler、
     * 注册日志指标 observer，并把 scheduler 加入 L1 基础设施生命周期。该方法非线程安全，只应在
     * runtime 启动前的单线程装配阶段调用；不会执行用户任务或修改游戏业务数据。</p>
     *
     * @param builder 运行时装配构建器；不可为空，启用时会追加基础设施生命周期组件。
     * @param config 统一配置；不可为空。
     * @param logAppender 标准安全日志写入端口；不可为空。
     * @param monitorRuntime 监控运行时；不可为空。
     * @param executors Starter 受管执行器；不可为空，background executor 必须非内联。
     * @return scheduler；未启用时为空，启用时非空且尚未启动。
     * @throws ZeroException 当配置非法、后台执行器可能内联或指标注册失败时抛出。
     */
    public static Optional<LocalManagedScheduler> configure(
            final ZeroRuntimeBuilder builder,
            final ZeroConfig config,
            final LogAppender logAppender,
            final MonitorRuntime monitorRuntime,
            final ZeroRuntimeExecutors executors) {
        ZeroRuntimeBuilder checkedBuilder = Objects.requireNonNull(builder, "builder");
        ZeroConfig checkedConfig = Objects.requireNonNull(config, "config");
        LogAppender checkedLogAppender = Objects.requireNonNull(logAppender, "logAppender");
        MonitorRuntime checkedMonitor = Objects.requireNonNull(monitorRuntime, "monitorRuntime");
        ZeroRuntimeExecutors checkedExecutors = Objects.requireNonNull(executors, "executors");
        if (!enabled(checkedConfig)) {
            return Optional.empty();
        }
        if (checkedExecutors.backgroundMayInline()) {
            throw invalid("managed scheduler requires a non-inline background executor", null);
        }
        LocalManagedSchedulerOptions options = options(checkedConfig);
        LoggingScheduledTaskObserver observer = new LoggingScheduledTaskObserver(
                checkedLogAppender,
                checkedMonitor.registry());
        LocalManagedScheduler scheduler = new LocalManagedScheduler(
                checkedExecutors.backgroundExecutor(),
                observer,
                options);
        checkedBuilder.addInfrastructureLifecycleComponent(scheduler);
        return Optional.of(scheduler);
    }

    private static LocalManagedSchedulerOptions options(final ZeroConfig config) {
        int maxTasks = ZeroStarterConfigParser.positiveInt(
                config,
                ZeroRuntimeConfigKeys.SCHEDULER_MAX_TASKS,
                ZeroRuntimeConfigKeys.DEFAULT_SCHEDULER_MAX_TASKS,
                SchedulerErrorCode.INVALID_OPTIONS);
        int maxInFlight = ZeroStarterConfigParser.positiveInt(
                config,
                ZeroRuntimeConfigKeys.SCHEDULER_MAX_IN_FLIGHT,
                ZeroRuntimeConfigKeys.DEFAULT_SCHEDULER_MAX_IN_FLIGHT,
                SchedulerErrorCode.INVALID_OPTIONS);
        long stopTimeoutMillis = ZeroStarterConfigParser.positiveLong(
                config,
                ZeroRuntimeConfigKeys.SCHEDULER_STOP_TIMEOUT_MILLIS,
                ZeroRuntimeConfigKeys.DEFAULT_SCHEDULER_STOP_TIMEOUT_MILLIS,
                SchedulerErrorCode.INVALID_OPTIONS);
        String threadNamePrefix = ZeroStarterConfigParser.nonBlank(
                config,
                ZeroRuntimeConfigKeys.SCHEDULER_THREAD_NAME_PREFIX,
                ZeroRuntimeConfigKeys.DEFAULT_SCHEDULER_THREAD_NAME_PREFIX,
                SchedulerErrorCode.INVALID_OPTIONS);
        try {
            return new LocalManagedSchedulerOptions(
                    maxTasks,
                    maxInFlight,
                    Duration.ofMillis(stopTimeoutMillis),
                    threadNamePrefix);
        } catch (IllegalArgumentException ex) {
            throw invalid("scheduler options are invalid: " + ex.getMessage(), ex);
        }
    }

    private static ZeroException invalid(final String message, final Throwable cause) {
        return ZeroException.of(SchedulerErrorCode.INVALID_OPTIONS, message, cause);
    }
}
