package group.zn.zero.net.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.net.http.HttpResponse;
import group.zn.zero.security.SecurityContext;
import group.zn.zero.security.SecurityContextBridge;
import group.zn.zero.security.SecurityMetadataAssertion;
import group.zn.zero.security.SecurityMetadataHttpCodec;
import group.zn.zero.security.SecurityMetadataSnapshot;
import group.zn.zero.security.SecurityMetadataVerifier;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** HTTP 请求安全边界与同连接身份隔离回归。 @author zn */
class HttpSecurityBoundaryTest {
    /** 确定性本地签名器。 */
    private final SecurityMetadataAssertion assertion = SecurityMetadataAssertion.digest(new byte[] {7, 8});

    /** 无 verifier、伪造签名、过期或重复头均不能进入业务处理器。 */
    @Test
    void rejectsUnverifiedExpiredAndDuplicateHeaders() {
        assertRejected(SecurityMetadataVerifier.failClosed(), signed("alice", false), false);
        SecurityMetadataSnapshot valid = signed("alice", false);
        SecurityMetadataSnapshot forged = new SecurityMetadataSnapshot("admin", valid.transport(),
                valid.peerAddress(), valid.trustedSourceAddress(), valid.traceId(), valid.correlationId(),
                Set.of("admin"), valid.assertionReference(), valid.issuedAt(), valid.expiresAt(), valid.signature());
        assertRejected(SecurityMetadataAssertion.verifier(assertion), forged, false);
        assertRejected(SecurityMetadataAssertion.verifier(assertion), signed("alice", true), false);
        assertRejected(SecurityMetadataAssertion.verifier(assertion), valid, true);
    }

    /** 两个流水线请求完成认证后仍各自持有身份，无头请求保持匿名。 */
    @Test
    void identityIsRequestScopedWithCaseInsensitiveHeaderLookup() {
        Queue<Runnable> businessTasks = new ArrayDeque<>();
        List<String> subjects = new ArrayList<>();
        EmbeddedChannel channel = new EmbeddedChannel(new NettyHttpServer.NettyHttpChannelHandler(request -> {
            subjects.add(SecurityContextBridge.current().map(SecurityContext::subject).orElse("anonymous"));
            return CompletableFuture.completedFuture(HttpResponse.text(200, "ok"));
        }, businessTasks::add, SecurityMetadataAssertion.verifier(assertion)));
        try {
            channel.writeInbound(request(signed("alice", false)));
            channel.writeInbound(request(signed("bob", false)));
            businessTasks.remove().run();
            businessTasks.remove().run();
            channel.runPendingTasks();
            while (!businessTasks.isEmpty()) businessTasks.remove().run();
            channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
            businessTasks.remove().run();
            channel.runPendingTasks();
            assertEquals(List.of("alice", "bob", "anonymous"), subjects);
            assertTrue(SecurityContextBridge.current().isEmpty());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private void assertRejected(final SecurityMetadataVerifier verifier,
            final SecurityMetadataSnapshot snapshot, final boolean duplicate) {
        List<String> calls = new ArrayList<>();
        EmbeddedChannel channel = new EmbeddedChannel(new NettyHttpServer.NettyHttpChannelHandler(request -> {
            calls.add(request.uri());
            return CompletableFuture.completedFuture(HttpResponse.text(200, "unexpected"));
        }, Runnable::run, verifier));
        try {
            DefaultFullHttpRequest request = request(snapshot);
            if (duplicate) request.headers().add(SecurityMetadataHttpCodec.HEADER, SecurityMetadataHttpCodec.encode(snapshot));
            channel.writeInbound(request);
            channel.runPendingTasks();
            FullHttpResponse response = channel.readOutbound();
            try {
                assertEquals(401, response.status().code());
                assertTrue(calls.isEmpty());
            } finally {
                response.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private DefaultFullHttpRequest request(final SecurityMetadataSnapshot snapshot) {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        request.headers().set("x-zero-security-metadata", SecurityMetadataHttpCodec.encode(snapshot));
        return request;
    }

    private SecurityMetadataSnapshot signed(final String subject, final boolean expired) {
        Instant issued = Instant.now().minusSeconds(60);
        SecurityContext context = new SecurityContext(subject, issued, issued.plusSeconds(expired ? 30 : 120),
                "http", "peer", "trusted", "trace", "corr", Set.of("read"), Map.of());
        return SecurityMetadataAssertion.signed(context, "assertion-" + subject, assertion);
    }
}
