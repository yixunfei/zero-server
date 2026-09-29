package group.zn.zero.net.kcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.lifecycle.ConnectionLifecycleObserver;
import group.zn.zero.net.lifecycle.NetworkAdmissionDecision;
import group.zn.zero.net.lifecycle.NetworkRateLimiter;
import group.zn.zero.net.lifecycle.ProductionNetworkConfig;
import group.zn.zero.net.lifecycle.ProductionNetworkLifecycle;
import group.zn.zero.net.lifecycle.SecurityNetworkPolicy;
import group.zn.zero.net.netty.NettyTcpServer;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import group.zn.zero.security.AuthenticationProvider;
import group.zn.zero.security.ReplayProtection;
import group.zn.zero.security.SecurityChain;
import group.zn.zero.security.SecurityContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 可执行 Java 联调示例：TLS 登录 -> 票据 -> KCP 回显 -> TCP 回退 -> 旧票据拒绝。
 * 测试组合根拥有执行器与临时证书；生产使用 runtime 执行器与真实身份服务。
 * @author zn
 */
@Timeout(20)
class KcpTcpLoginExampleTest {
    /** 示例专用业务 ID，不占用框架协议 ID。 */
    private static final int TICKET = 100;
    /** 示例专用回退请求 ID。 */
    private static final int FALLBACK = 101;
    /** 双传输复用的业务 codec。 */
    private static final ZeroBinaryFrameCodec CODEC = new ZeroBinaryFrameCodec();

    /** 真实 TLS 验证证书和主机名，秘密不经明文 UDP 下发。 */
    @Test void loginTicketKcpAndExplicitTcpFallback() throws Exception {
        SelfSignedCertificate certificate = new SelfSignedCertificate("localhost");
        try (var workers = Executors.newFixedThreadPool(3)) {
            KcpServer kcp = new KcpServer(ServerOptions.kcp("127.0.0.1", 0).withIoThreads(1, 1),
                    KcpOptions.defaults(), CODEC,
                    (connection, frame) -> CompletableFuture.completedFuture(List.of(frame)),
                    new ConnectionListener() { }, workers, null);
            SecurityChain security = security();
            var policy = new SecurityNetworkPolicy((connection, frame) -> NetworkAdmissionDecision.allow(), security);
            var lifecycle = new ProductionNetworkLifecycle(ProductionNetworkConfig.defaults("kcp-demo")
                    .withTlsRequired(true), policy, security, NetworkRateLimiter.permitAll(),
                    ConnectionLifecycleObserver.noOp(), workers, workers);
            CompletableFuture<Void> ticketSent = new CompletableFuture<>();
            var tcp = new NettyTcpServer(ServerOptions.tcp("127.0.0.1", 0).withIoThreads(1, 1), CODEC,
                    (connection, frame) -> frame.protocolId() == FALLBACK
                            ? kcp.fallbackToTcp(connection, ByteBuffer.wrap(frame.payload()).getInt())
                                    .thenApply(ignored -> List.of(frame))
                            : CompletableFuture.completedFuture(List.of(frame)),
                    new ConnectionListener() {
                        @Override public void onOpen(final IConnection connection) {
                            kcp.issueTicket(connection).thenCompose(ticket -> connection.sendFrame(ticketFrame(ticket)))
                                    .whenComplete((ignored, failure) -> {
                                        if (failure == null) ticketSent.complete(null);
                                        else ticketSent.completeExceptionally(failure);
                                    });
                        }
                    }, workers, lifecycle, SslContextBuilder.forServer(certificate.key(), certificate.cert()).build());
            try {
                kcp.start();
                tcp.start();
                try (SSLSocket socket = client(certificate, tcp.boundPort())) {
                    write(socket, new ProtocolFrame(1, 1, 0, null, "demo-secret".getBytes(StandardCharsets.UTF_8)));
                    KcpTicket ticket = ticket(read(socket));
                    ticketSent.get(3, TimeUnit.SECONDS);
                    try (var peer = new KcpTestPeer(ticket, kcp.boundPort(), KcpOptions.defaults())) {
                        ProtocolFrame request = KcpServerTest.frame(2);
                        peer.send(request);
                        peer.until(() -> peer.received.size() == 1);
                        assertArrayEquals(request.payload(), peer.received.getFirst().payload());
                        write(socket, new ProtocolFrame(FALLBACK, 1, 0, null,
                                ByteBuffer.allocate(4).putInt(ticket.conv()).array()));
                        assertEquals(FALLBACK, read(socket).protocolId());
                        write(socket, KcpServerTest.frame(3));
                        assertEquals(3, read(socket).protocolId());
                        peer.send(KcpServerTest.frame(4));
                        peer.until(() -> kcp.rejectedDatagrams() > 0);
                        assertEquals(1, peer.received.size());
                    }
                }
                assertEquals(0, kcp.failureCount());
            } finally { tcp.stop(); kcp.stop(); }
        } finally { certificate.delete(); }
    }
    private static SecurityChain security() throws Exception {
        String credential = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("demo-secret".getBytes(StandardCharsets.UTF_8)));
        return new SecurityChain(request -> {
            if (!credential.equals(request.credentialReference())) {
                return CompletableFuture.completedFuture(AuthenticationProvider.AuthenticationResult.rejected());
            }
            Instant now = Instant.now();
            return CompletableFuture.completedFuture(AuthenticationProvider.AuthenticationResult.accepted(
                    new SecurityContext("demo-player", now, now.plusSeconds(60), "tcp", request.peerAddress(),
                            request.peerAddress(), "demo-trace", "demo-login", Set.of("network.request"), Map.of())));
        }, request -> CompletableFuture.completedFuture(ReplayProtection.ReplayDecision.ACCEPTED), null, false);
    }
    private static SSLSocket client(final SelfSignedCertificate certificate, final int port) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setCertificateEntry("local-demo", certificate.cert());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket("localhost", port);
        socket.setSoTimeout(3000);
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        socket.startHandshake();
        return socket;
    }
    private static ProtocolFrame ticketFrame(final KcpTicket ticket) {
        byte[] body = ByteBuffer.allocate(44).putInt(ticket.conv()).putLong(ticket.expiresAt().toEpochMilli())
                .put(ticket.key()).array();
        return new ProtocolFrame(TICKET, 1, 0, null, body);
    }
    private static KcpTicket ticket(final ProtocolFrame frame) {
        assertEquals(TICKET, frame.protocolId());
        ByteBuffer buffer = ByteBuffer.wrap(frame.payload());
        int conv = buffer.getInt();
        Instant expires = Instant.ofEpochMilli(buffer.getLong());
        byte[] key = new byte[32];
        buffer.get(key);
        assertTrue(expires.isAfter(Instant.now()));
        return new KcpTicket(conv, key, expires);
    }
    private static void write(final SSLSocket socket, final ProtocolFrame frame) throws Exception {
        byte[] bytes = CODEC.encode(frame);
        var output = new DataOutputStream(socket.getOutputStream());
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
    }
    private static ProtocolFrame read(final SSLSocket socket) throws Exception {
        var input = new DataInputStream(socket.getInputStream());
        int length = input.readInt();
        if (length < 0 || length > 65536) throw new IllegalArgumentException("invalid example frame");
        return CODEC.decode(input.readNBytes(length));
    }
}
