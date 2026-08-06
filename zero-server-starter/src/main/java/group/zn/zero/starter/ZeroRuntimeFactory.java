package group.zn.zero.starter;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.config.ZeroConfigLoader;
import group.zn.zero.log.LogSink;
import group.zn.zero.log.SystemLoggerLogSink;
import java.util.Map;
import java.util.Objects;

/**
 * starter 默认运行时组件工厂。
 *
 * <p>本工厂只创建本地无 Docker 默认组件。真实 Kafka、MongoDB、Redis、PostgreSQL 和 Nacos Adapter
 * 由业务项目或后续 opt-in 工厂显式接入。
 *
 * @author zn
 */
public final class ZeroRuntimeFactory {

    private ZeroRuntimeFactory() {
    }

    /**
     * 创建标准本地默认运行时组件。
     *
     * <p>配置来源为标准外部配置入口，并合并本地默认值。
     *
     * @return 运行时组件；不可为空；未启动；线程安全性由内部组件声明。
     */
    public static ZeroRuntimeComponents localDefault() {
        return localDefault(ZeroConfigLoader.loadStandard(localDefaults()));
    }

    /**
     * 创建本地默认运行时组件。
     *
     * @param config 框架配置；不可为空。
     * @return 运行时组件；不可为空；未启动；线程安全性由内部组件声明。
     */
    public static ZeroRuntimeComponents localDefault(final ZeroConfig config) {
        return localBuilder(config).build();
    }

    /**
     * 创建带自定义日志 sink 的本地默认运行时组件。
     *
     * @param config 框架配置；不可为空。
     * @param terminalLogSink 终端日志落地 SPI；不可为空，内部始终由安全 pipeline 包装。
     * @return 运行时组件；不可为空；未启动；线程安全性由内部组件声明。
     */
    public static ZeroRuntimeComponents localDefault(final ZeroConfig config, final LogSink terminalLogSink) {
        return localBuilder(config, terminalLogSink).build();
    }

    /**
     * 创建带自定义日志 sink 和执行器装配点的本地默认运行时组件。
     *
     * @param config 框架配置；不可为空。
     * @param terminalLogSink 终端日志落地 SPI；不可为空，内部始终由安全 pipeline 包装。
     * @param executors 执行器装配点；不可为空。
     * @return 运行时组件；不可为空；未启动；线程安全性由内部组件声明。
     */
    public static ZeroRuntimeComponents localDefault(
            final ZeroConfig config,
            final LogSink terminalLogSink,
            final ZeroRuntimeExecutors executors) {
        return localBuilder(config, terminalLogSink, executors).build();
    }

    /**
     * 创建标准本地默认运行时装配构建器。
     *
     * <p>配置来源为标准外部配置入口，并合并本地默认值。返回的构建器可继续覆盖单个组件槽位。
     *
     * @return 运行时装配构建器；不可为空；非线程安全。
     */
    public static ZeroRuntimeBuilder localBuilder() {
        return localBuilder(ZeroConfigLoader.loadStandard(localDefaults()));
    }

    /**
     * 创建本地默认运行时装配构建器。
     *
     * @param config 框架配置；不可为空。
     * @return 运行时装配构建器；不可为空；非线程安全。
     * @throws NullPointerException 当配置为空时抛出。
     */
    public static ZeroRuntimeBuilder localBuilder(final ZeroConfig config) {
        return localBuilder(
                config,
                SystemLoggerLogSink.named(ZeroServerApplication.class.getName()),
                ZeroRuntimeExecutors.direct());
    }

    /**
     * 创建带自定义默认日志 sink 的本地默认运行时装配构建器。
     *
     * @param config 框架配置；不可为空。
     * @param terminalLogSink 终端日志落地 SPI；不可为空，内部始终由安全 pipeline 包装。
     * @return 运行时装配构建器；不可为空；非线程安全。
     * @throws NullPointerException 当配置或日志 sink 为空时抛出。
     */
    public static ZeroRuntimeBuilder localBuilder(final ZeroConfig config, final LogSink terminalLogSink) {
        return localBuilder(config, terminalLogSink, ZeroRuntimeExecutors.direct());
    }

    /**
     * 创建带自定义默认日志 sink 和执行器装配点的本地默认运行时装配构建器。
     *
     * @param config 框架配置；不可为空。
     * @param terminalLogSink 终端日志落地 SPI；不可为空，内部始终由安全 pipeline 包装。
     * @param executors 执行器装配点；不可为空。
     * @return 运行时装配构建器；不可为空；非线程安全。
     * @throws NullPointerException 当任一必要参数为空时抛出。
     */
    public static ZeroRuntimeBuilder localBuilder(
            final ZeroConfig config,
            final LogSink terminalLogSink,
            final ZeroRuntimeExecutors executors) {
        return new ZeroRuntimeBuilder(
                Objects.requireNonNull(config, "config"),
                Objects.requireNonNull(terminalLogSink, "terminalLogSink"),
                Objects.requireNonNull(executors, "executors"));
    }

    /**
     * 返回本地默认配置。
     *
     * @return 不可变、无序、非空、线程安全的本地默认配置。
     */
    public static Map<String, String> localDefaults() {
        return Map.of(
                ZeroRuntimeConfigKeys.ZERO_MODE, ZeroRuntimeConfigKeys.MODE_LOCAL,
                ZeroRuntimeConfigKeys.ZERO_NAME, ZeroRuntimeConfigKeys.DEFAULT_NAME);
    }

    static ZeroConfig mergeLocalDefaults(final ZeroConfig config) {
        Map<String, String> values = new java.util.LinkedHashMap<>(localDefaults());
        values.putAll(Objects.requireNonNull(config, "config").asMap());
        return new MapZeroConfig(values);
    }
}
