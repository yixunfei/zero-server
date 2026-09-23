package group.zn.zero.starter.production;

import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.config.ZeroConfigLoader;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.log.LogSink;
import group.zn.zero.log.SystemLoggerLogSink;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import group.zn.zero.runtime.production.ProductionAdapterException;
import group.zn.zero.runtime.production.ProductionProfiles;
import group.zn.zero.runtime.production.ZeroProductionAssemblyReport;
import group.zn.zero.runtime.production.ZeroProductionRuntime;
import group.zn.zero.runtime.production.ZeroProductionRuntimeConfigKeys;
import java.util.Objects;

/**
 * 生产与外部测试 runtime 装配工厂。
 *
 * <p>该工厂位于独立模块中，显式承载真实 Adapter 依赖；`zero-server-starter` 的
 * {@code LocalRuntime.create} 和 {@code LocalRuntime.builder} 保持无 Docker、无真实中间件连接的默认语义。
 *
 * @author zn
 */
public final class ZeroProductionRuntimeFactory {

    private ZeroProductionRuntimeFactory() {
    }

    /**
     * 基于标准外部配置创建 production runtime。
     *
     * <p>工厂调用线程安全；会解析进程配置并创建未启动的外部 client，不修改业务数据，调用方必须关闭返回值。</p>
     *
     * @return production runtime；不可为空，未启动，线程安全性由内部组件声明。
     * @throws ProductionAdapterException 配置缺失、非法或 client 创建失败时抛出安全异常。
     */
    public static ZeroProductionRuntime productionDefault() {
        return productionBuilder(ZeroConfigLoader.loadStandard()).build();
    }

    /**
     * 创建 production runtime builder。
     *
     * <p>工厂调用线程安全；只复制配置，不创建外部 client、不修改业务数据。</p>
     *
     * @param config 统一配置；不可为空。
     * @return production runtime builder；不可为空，非线程安全。
     * @throws NullPointerException 配置为空时抛出。
     * @throws ZeroException 配置中的 runtime mode 与 production 不一致时抛出。
     */
    public static ZeroProductionRuntimeBuilder productionBuilder(final ZeroConfig config) {
        return defaultBuilder(ZeroProductionRuntimeConfigKeys.MODE_PRODUCTION, config);
    }

    /**
     * 创建带自定义日志 sink 的 production runtime builder。
     *
     * <p>工厂调用线程安全；只复制配置和 SPI 引用，不创建外部 client、不修改业务数据。</p>
     *
     * @param config 统一配置；不可为空。
     * @param terminalLogSink 终端日志落地 SPI；不可为空，内部始终由安全 pipeline 包装。
     * @return production runtime builder；不可为空，非线程安全。
     * @throws NullPointerException 配置或日志 SPI 为空时抛出。
     * @throws ZeroException 配置中的 runtime mode 与 production 不一致时抛出。
     */
    public static ZeroProductionRuntimeBuilder productionBuilder(
            final ZeroConfig config,
            final LogSink terminalLogSink) {
        return builder(
                ZeroProductionRuntimeConfigKeys.MODE_PRODUCTION,
                config,
                terminalLogSink,
                ZeroRuntimeExecutors.direct());
    }

    /**
     * 创建 external-test runtime builder。
     *
     * <p>工厂调用线程安全；只复制配置，不连接真实组件、不修改业务数据。</p>
     *
     * @param config 统一配置；不可为空。
     * @return external-test runtime builder；不可为空，非线程安全。
     * @throws NullPointerException 配置为空时抛出。
     * @throws ZeroException 配置中的 runtime mode 与 external-test 不一致时抛出。
     */
    public static ZeroProductionRuntimeBuilder externalTestBuilder(final ZeroConfig config) {
        return defaultBuilder(ZeroProductionRuntimeConfigKeys.MODE_EXTERNAL_TEST, config);
    }

    /**
     * 创建 external-test runtime builder。
     *
     * <p>工厂调用线程安全；只复制配置和 SPI 引用，不连接真实组件、不修改业务数据。</p>
     *
     * @param config 统一配置；不可为空。
     * @param terminalLogSink 终端日志落地 SPI；不可为空，内部始终由安全 pipeline 包装。
     * @return external-test runtime builder；不可为空，非线程安全。
     * @throws NullPointerException 配置或日志 SPI 为空时抛出。
     * @throws ZeroException 配置中的 runtime mode 与 external-test 不一致时抛出。
     */
    public static ZeroProductionRuntimeBuilder externalTestBuilder(
            final ZeroConfig config,
            final LogSink terminalLogSink) {
        return builder(
                ZeroProductionRuntimeConfigKeys.MODE_EXTERNAL_TEST,
                config,
                terminalLogSink,
                ZeroRuntimeExecutors.direct());
    }

    /**
     * 创建指定 profile 的 runtime builder。
     *
     * <p>工厂调用线程安全；复制配置并保存执行器/SPI 引用，不创建 client、不修改业务数据。</p>
     *
     * @param profile profile；当前只允许 `production` 或 `external-test`。
     * @param config 统一配置；不可为空。
     * @param terminalLogSink 终端日志落地 SPI；不可为空，内部始终由安全 pipeline 包装。
     * @param executors 执行器装配点；不可为空。
     * @return runtime builder；不可为空，非线程安全。
     * @throws NullPointerException 任一必填参数为空时抛出。
     * @throws ZeroException profile 非法或配置 mode 与 profile 不一致时抛出。
     */
    public static ZeroProductionRuntimeBuilder builder(
            final String profile,
            final ZeroConfig config,
            final LogSink terminalLogSink,
            final ZeroRuntimeExecutors executors) {
        LogSink checked = Objects.requireNonNull(terminalLogSink, "terminalLogSink");
        return new ZeroProductionRuntimeBuilder(
                profile,
                ProductionProfiles.merge(profile, config),
                () -> checked,
                Objects.requireNonNull(executors, "executors"));
    }

    /**
     * 生成 production 配置诊断报告，不创建真实 Adapter。
     *
     * <p>线程安全；只读取并复制配置，不创建 client、不修改业务数据。</p>
     *
     * @param config 统一配置；不可为空。
     * @return 装配诊断报告；不可为空，线程安全。
     * @throws NullPointerException 配置为空时抛出。
     * @throws ProductionAdapterException 配置缺失或非法时抛出安全异常。
     */
    public static ZeroProductionAssemblyReport diagnoseProduction(final ZeroConfig config) {
        return productionBuilder(config).diagnose();
    }

    /**
     * 生成 external-test 配置诊断报告，不创建真实 Adapter。
     *
     * <p>线程安全；只读取并复制配置，不创建 client、不修改业务数据。</p>
     *
     * @param config 统一配置；不可为空。
     * @return 装配诊断报告；不可为空，线程安全。
     * @throws NullPointerException 配置为空时抛出。
     * @throws ProductionAdapterException 配置缺失或非法时抛出安全异常。
     */
    public static ZeroProductionAssemblyReport diagnoseExternalTest(final ZeroConfig config) {
        return externalTestBuilder(config).diagnose();
    }

    private static ZeroProductionRuntimeBuilder defaultBuilder(final String profile, final ZeroConfig config) {
        return new ZeroProductionRuntimeBuilder(profile, ProductionProfiles.merge(profile, config),
                () -> SystemLoggerLogSink.named(ZeroProductionRuntimeFactory.class.getName()),
                ZeroRuntimeExecutors.direct());
    }
}
