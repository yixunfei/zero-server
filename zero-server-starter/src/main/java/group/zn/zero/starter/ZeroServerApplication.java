package group.zn.zero.starter;

import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.lifecycle.AbstractLifecycle;
import group.zn.zero.log.LogLevel;
import group.zn.zero.log.LogOperation;
import group.zn.zero.log.LogResult;
import group.zn.zero.log.LogSink;
import group.zn.zero.log.LogSource;
import group.zn.zero.log.LogType;
import group.zn.zero.log.ZeroLogRecord;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeConfigKeys;
import group.zn.zero.runtime.log.LogRuntime;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * zeroServer 本地最小启动入口。
 *
 * @author zn
 */
public final class ZeroServerApplication extends AbstractLifecycle {

    /**
     * 运行时组件。
     */
    private final GameRuntime runtime;

    /**
     * 创建应用实例。
     *
     * @param config 框架配置；不可为空。
     * @throws NullPointerException 当配置为空时抛出。
     */
    public ZeroServerApplication(final ZeroConfig config) {
        this(LocalRuntime.create(config));
    }

    /**
     * 创建应用实例。
     *
     * @param config 框架配置；不可为空。
     * @param terminalLogSink 启动日志终端落地 SPI；不可为空；内部始终由安全 pipeline 包装。
     * @throws NullPointerException 当配置或终端日志 SPI 为空时抛出。
     */
    public ZeroServerApplication(final ZeroConfig config, final LogSink terminalLogSink) {
        this(LocalRuntime.create(config, terminalLogSink));
    }

    /**
     * 创建应用实例。
     *
     * @param runtime 模块化运行时；不可为空；由应用负责启动和停止。
     * @throws NullPointerException 当运行时为空时抛出。
     */
    public ZeroServerApplication(final GameRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /**
     * 返回框架配置。
     *
     * @return 配置对象；不可为空；线程安全。
     */
    public ZeroConfig config() {
        return runtime.require(RuntimeBasics.CONFIG);
    }

    /**
     * 返回运行时组件。
     *
     * @return 运行时组件；不可为空；线程安全。
     */
    public GameRuntime runtime() {
        return runtime;
    }

    /**
     * 启动运行时组件并写入启动日志。
     *
     * <p>该方法只写入启动观测记录，不修改业务状态；线程安全性由生命周期基类的同步启动流程保证。
     */
    @Override
    protected void doStart() {
        runtime.start();
        String mode = config().get(ZeroRuntimeConfigKeys.ZERO_MODE).orElse("unknown");
        String name = config().get(ZeroRuntimeConfigKeys.ZERO_NAME).orElse("unknown");
        runtime.require(LogRuntime.LOG_APPENDER).append(ZeroLogRecord.create(
                Instant.now(),
                LogLevel.INFO,
                LogType.RUNTIME,
                new LogSource(name, mode, "zero-server-starter"),
                new LogOperation("runtime-start", LogResult.SUCCESS, null),
                "bootstrap",
                "zeroServer started",
                Map.of(
                        "mode", mode,
                        "name", name)));
    }

    /**
     * 停止运行时组件。
     */
    @Override
    protected void doStop() {
        runtime.stop();
    }

    /**
     * 命令行启动入口。
     *
     * @param args 命令行参数；当前版本不读取。
     */
    public static void main(final String[] args) {
        ZeroServerApplication application = new ZeroServerApplication(LocalRuntime.create());
        application.start();
        application.stop();
    }
}
