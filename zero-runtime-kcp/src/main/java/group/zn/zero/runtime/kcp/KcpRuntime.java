package group.zn.zero.runtime.kcp;

import group.zn.zero.core.lifecycle.AbstractLifecycle;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.ServerFrameHandler;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.kcp.KcpAlgorithms;
import group.zn.zero.net.kcp.KcpOptions;
import group.zn.zero.net.kcp.KcpProfile;
import group.zn.zero.net.kcp.KcpServer;
import group.zn.zero.net.kcp.KcpSessionServices;
import group.zn.zero.net.kcp.KcpSnapshot;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.assembly.RuntimeModule;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import group.zn.zero.runtime.net.NetworkRuntime;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.RuntimeProviders;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** 可选 KCP 装配模块；不安装即不启动资源，可按不同名字安装多个场景监听器。 @author zn */
public final class KcpRuntime {
    private KcpRuntime() { }
    /** @param name 配置级稳定名称。 @return 服务能力 key；不可变、线程安全。 */
    public static ComponentKey<KcpServer> server(final String name) {
        requireName(name); return ComponentKey.single("zero.kcp." + name + ".server", KcpServer.class);
    }
    /**
     * 创建可直接 install 的场景模块，无 IO 副作用；handler 使用 runtime 逻辑执行域。
     * @param name 监听器名。 @param host 绑定地址。 @param port UDP 端口。
     * @param profile 场景。 @param handler 异步业务 handler。
     * @return 不可变装配模块；线程安全。
     */
    public static RuntimeModule module(final String name, final String host, final int port,
            final KcpProfile profile, final ServerFrameHandler handler) {
        return module(name, ServerOptions.kcp(host, port).withIoThreads(1, 1), profile.options(), handler, new ConnectionListener() { });
    }
    /**
     * 显式高级装配，配置来源可覆盖默认值；不创建线程，校验失败不启动 socket。
     * @param name 配置级名称。 @param network socket 设置。 @param defaults KCP 设置。
     * @param handler 业务入口。 @param listener 连接观察器。
     * @return 不可变可选模块；线程安全。
     */
    public static RuntimeModule module(final String name, final ServerOptions network, final KcpOptions defaults,
            final ServerFrameHandler handler, final ConnectionListener listener) {
        return module(name, network, defaults, handler, listener, KcpSessionServices.local());
    }
    /** 集群装配入口；注入节点身份、租约和会话所有权 SPI，不改变基础模块依赖方向。 */
    public static RuntimeModule module(final String name, final ServerOptions network, final KcpOptions defaults,
            final ServerFrameHandler handler, final ConnectionListener listener, final KcpSessionServices sessionServices) {
        return module(name, network, defaults, handler, listener, sessionServices, KcpAlgorithms.defaults());
    }
    /**
     * 创建支持自定义保护/FEC 工厂的集群装配模块；线程安全，不启动资源。
     * @param name 监听器名。 @param network 网络设置。 @param defaults 默认场景和传输策略。
     * @param handler 有序业务入口。 @param listener 生命周期观察器。
     * @param sessionServices 调用方持有的所有权存储与租约配置。
     * @param algorithms 两端显式约定的不可变算法注册表，不通过网络装载代码。
     * @return 不可变模块；未知策略或非法组合在创建服务时拒绝，不自动降级。
     */
    public static RuntimeModule module(final String name, final ServerOptions network, final KcpOptions defaults,
            final ServerFrameHandler handler, final ConnectionListener listener, final KcpSessionServices sessionServices,
            final KcpAlgorithms algorithms) {
        requireName(name); Objects.requireNonNull(handler); Objects.requireNonNull(listener);
        Objects.requireNonNull(sessionServices); Objects.requireNonNull(algorithms);
        var schema = new KcpRuntimeConfig(name, Objects.requireNonNull(network), Objects.requireNonNull(defaults));
        var key = server(name);
        var descriptor = ComponentDescriptor.builder(schema.id()).provide(key).require(RuntimeBasics.EXECUTORS)
                .optional(NetworkRuntime.IO_RESOURCES).configSchema(schema.schema());
        boolean monitoringAvailable = monitoringAvailable();
        if (monitoringAvailable) KcpRuntimeMonitoring.declareDependency(descriptor);
        var provider = RuntimeProviders.create(descriptor.build(), context -> {
            KcpOptions options = schema.options(context.config());
            ServerOptions socket = schema.network(context.config());
            ZeroRuntimeExecutors executors = context.require(RuntimeBasics.EXECUTORS);
            if (executors.backgroundMayInline() || executors.remoteIoMayInline()) {
                throw new IllegalArgumentException("KCP runtime requires managed non-inline executors (localPrototype or production)");
            }
            var server = new KcpServer(socket, options, new ZeroBinaryFrameCodec(), handler, listener,
                    executors.logicExecutor(), context.optional(NetworkRuntime.IO_RESOURCES).orElse(null),
                    algorithms, sessionServices);
            Consumer<KcpSnapshot> telemetry = monitoringAvailable ? KcpRuntimeMonitoring.create(name, context) : null;
            return ComponentContribution.builder().bind(key, server).lifecycle(new Managed(server, telemetry, executors)).build();
        });
        return RuntimeModule.of(schema.id().value(), List.of(provider), key);
    }
    /** 只探测可选模块是否在类路径中；不初始化监控实现或创建资源。 */
    private static boolean monitoringAvailable() {
        try {
            Class.forName("group.zn.zero.runtime.monitor.MonitorRuntimeComponent", false, KcpRuntime.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException absent) {
            // optional 依赖缺席是合法的最小装配；损坏依赖的链接错误仍向上传播。
            return false;
        }
    }
    private static void requireName(final String value) {
        if (value == null || !value.matches("[a-z][a-z0-9-]{0,31}")) throw new IllegalArgumentException("invalid KCP listener name");
    }
    /** 服务与订阅共用生命周期，启动失败时原位回收。 @author zn */
    private static final class Managed extends AbstractLifecycle {
        /** 实际服务。 */
        private final KcpServer server;
        /** 可选遥测。 */
        private final Consumer<KcpSnapshot> telemetry;
        /** 受管资源，生命周期晚于本 provider。 */
        private final ZeroRuntimeExecutors executors;
        Managed(final KcpServer server, final Consumer<KcpSnapshot> telemetry, final ZeroRuntimeExecutors executors) {
            this.server = server; this.telemetry = telemetry; this.executors = executors;
        }
        @Override protected void doStart() {
            try {
                server.start();
                if (telemetry != null) server.watch(Duration.ofSeconds(1), executors.backgroundExecutor(), telemetry);
            } catch (RuntimeException failure) { server.stop(); throw failure; }
        }
        @Override protected void doStop() { server.stop(); }
    }
}
