package group.zn.zero.examples.kcp;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.kcp.KcpProfile;
import group.zn.zero.net.kcp.KcpOptions;
import group.zn.zero.net.kcp.KcpSessionServices;
import group.zn.zero.net.kcp.KcpServer;
import group.zn.zero.net.lifecycle.ConnectionLifecycleObserver;
import group.zn.zero.net.lifecycle.NetworkAdmissionDecision;
import group.zn.zero.net.lifecycle.NetworkRateLimiter;
import group.zn.zero.net.lifecycle.ProductionNetworkConfig;
import group.zn.zero.net.lifecycle.ProductionNetworkLifecycle;
import group.zn.zero.net.lifecycle.SecurityNetworkPolicy;
import group.zn.zero.net.netty.NettyTcpServer;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.assembly.RuntimeComposition;
import group.zn.zero.runtime.assembly.RuntimeProfile;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import group.zn.zero.runtime.kcp.KcpRuntime;
import group.zn.zero.security.AuthenticationProvider;
import group.zn.zero.security.ReplayProtection;
import group.zn.zero.security.SecurityChain;
import group.zn.zero.security.SecurityContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** 独立本机示例服务；临时证书与演示身份只在 --demo 入口使用。 @author zn */
public final class KcpDemoServer implements AutoCloseable {
    /** 临时证书，本实例负责删除。 */
    private final SelfSignedCertificate certificate;
    /** 受管执行域。 */
    private final ZeroRuntimeExecutors executors;
    /** 运行时，负责 KCP 与执行器关闭。 */
    private GameRuntime runtime;
    /** TLS 控制通道。 */
    private NettyTcpServer tcp;
    /** KCP 服务。 */
    private KcpServer kcp;
    /** @param profile 场景；创建并启动本机双通道，失败时释放所有已创建资源。 @throws Exception 启动失败。 */
    public KcpDemoServer(final KcpProfile profile) throws Exception {
        this(profile.options(), KcpSessionServices.local());
    }
    /**
     * 创建显式策略/节点的示例服务，使用真实 TLS 身份流程。
     * @param options 场景和传输配置。 @param services 所有权资源，由外层组合根持有。
     * @throws Exception 启动失败，释放本节点资源；只在组合根调用。
     */
    public KcpDemoServer(final KcpOptions options, final KcpSessionServices services) throws Exception {
        certificate = new SelfSignedCertificate("localhost");
        executors = ZeroRuntimeExecutors.localPrototype("kcp-demo", 1);
        try { start(options, services); } catch (Exception failure) { close(); throw failure; }
    }
    private void start(final KcpOptions options, final KcpSessionServices services) throws Exception {
        runtime = RuntimeComposition.builder(RuntimeProfile.local())
                .install(RuntimeBasics.module(new MapZeroConfig(Map.of()), executors))
                .install(KcpRuntime.module("game", ServerOptions.kcp("127.0.0.1", 0).withIoThreads(1, 1), options,
                        (connection, frame) -> CompletableFuture.completedFuture(List.of(frame)),
                        new ConnectionListener() { }, services)).build();
        runtime.start(); kcp = runtime.require(KcpRuntime.server("game"));
        SecurityChain security = security();
        var lifecycle = new ProductionNetworkLifecycle(ProductionNetworkConfig.defaults("kcp-demo").withTlsRequired(true),
                new SecurityNetworkPolicy((connection, frame) -> NetworkAdmissionDecision.allow(), security), security,
                NetworkRateLimiter.permitAll(), ConnectionLifecycleObserver.noOp(),
                executors.remoteIoExecutor(), executors.backgroundExecutor());
        tcp = new NettyTcpServer(ServerOptions.tcp("127.0.0.1", 0).withIoThreads(1, 1), KcpDemoWire.CODEC,
                (connection, frame) -> {
                    if (frame.protocolId() == KcpDemoWire.TICKET) return description(connection).thenApply(List::of);
                    if (frame.protocolId() == KcpDemoMigration.MIGRATE || frame.protocolId() == KcpDemoMigration.CLAIM) {
                        return KcpDemoMigration.handle(kcp, connection, frame).thenApply(List::of);
                    }
                    if (frame.protocolId() == KcpDemoWire.REVOKE) {
                        if (frame.payloadLength() != 4) throw new IllegalArgumentException("invalid demo revoke");
                        return kcp.fallbackToTcp(connection, ByteBuffer.wrap(frame.payload()).getInt()).thenApply(ignored -> List.of(frame));
                    }
                    return CompletableFuture.completedFuture(List.of(frame));
                }, new ConnectionListener() {
                    @Override public void onOpen(final IConnection connection) {
                        description(connection).thenCompose(connection::sendFrame).whenComplete((ignored, failure) -> {
                            if (failure != null) connection.close();
                        });
                    }
                }, executors.logicExecutor(), lifecycle,
                SslContextBuilder.forServer(certificate.key(), certificate.cert()).build());
        tcp.start();
    }
    private java.util.concurrent.CompletionStage<ProtocolFrame> description(final IConnection connection) {
        return kcp.issueConnectInfo(connection, "127.0.0.1", kcp.boundPort())
                .thenApply(info -> KcpDemoWire.frame(KcpDemoWire.TICKET, info.encode()));
    }
    private static SecurityChain security() throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("demo-secret".getBytes(StandardCharsets.UTF_8)));
        return new SecurityChain(request -> {
            if (!digest.equals(request.credentialReference())) {
                return CompletableFuture.completedFuture(AuthenticationProvider.AuthenticationResult.rejected());
            }
            Instant now = Instant.now();
            return CompletableFuture.completedFuture(AuthenticationProvider.AuthenticationResult.accepted(
                    new SecurityContext("demo-player", now, now.plusSeconds(300), "tcp", request.peerAddress(),
                            request.peerAddress(), "demo-trace", "demo-login", Set.of("network.request"), Map.of())));
        }, request -> CompletableFuture.completedFuture(ReplayProtection.ReplayDecision.ACCEPTED), null, false);
    }
    /** @return TLS 控制端口；启动完成后只读。 */
    public int port() { return tcp.boundPort(); }
    /** @return 本次公钥证书文件，不含私钥；只读。 */
    public java.io.File certificateFile() { return certificate.certificate(); }
    /** @return 服务观测句柄；仅用于示例输出。 */
    public KcpServer server() { return kcp; }
    /** 关闭 TLS、KCP、执行器并删除临时证书；幂等，由组合根调用。 */
    @Override public void close() {
        try { if (tcp != null) tcp.stop(); }
        finally { try { if (runtime != null) runtime.close(); else executors.close(); } finally { certificate.delete(); } }
    }
    /**
     * 启动独立本机服务，默认运行 60 秒便于手工客户端联调。
     * @param args --demo [PROFILE] [seconds]。 @throws Exception 启动或等待失败。
     */
    public static void main(final String[] args) throws Exception {
        if (args.length == 0 || !args[0].equals("--demo")) throw new IllegalArgumentException("explicit --demo required");
        KcpProfile profile = args.length > 1 ? KcpProfile.valueOf(args[1]) : KcpProfile.BALANCED;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 60;
        if (seconds < 1 || seconds > 600) throw new IllegalArgumentException("seconds must be 1..600");
        try (var server = new KcpDemoServer(profile)) {
            System.out.printf("READY profile=%s tcp=%d certificate=%s%n", profile, server.port(), server.certificateFile().getAbsolutePath());
            Thread.sleep(seconds * 1000L);
        }
    }
}
