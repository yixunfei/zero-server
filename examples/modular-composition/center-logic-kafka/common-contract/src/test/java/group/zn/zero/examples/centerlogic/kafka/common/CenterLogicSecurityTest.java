package group.zn.zero.examples.centerlogic.kafka.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.rpc.RpcCallOptions;
import group.zn.zero.rpc.RpcCallContext;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import group.zn.zero.rpc.client.RpcClientFactory;
import group.zn.zero.rpc.codec.RpcCodecRegistry;
import group.zn.zero.rpc.error.RpcErrorCode;
import group.zn.zero.rpc.spi.RpcTransport;
import group.zn.zero.security.SecurityContext;
import group.zn.zero.security.SecurityContextBridge;
import group.zn.zero.security.SecurityMetadataAssertion;
import group.zn.zero.security.SecurityMetadataSnapshot;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 无 broker 的双端身份接线：使用真实 typed client 生成 metadata，再经接收配方校验。 @author zn */
class CenterLogicSecurityTest {
    /** 测试专用密钥，不用于真实运行。 */
    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[32]);

    /** 主体、签名、到期和线程 scope 都由公开接线贯通。 */
    @Test void typedClientSignsScopedIdentityAndReceiverVerifiesIt() {
        var sender = new CenterLogicSecurity("run", SECRET);
        var receiver = new CenterLogicSecurity("run", SECRET);
        var captured = new AtomicReference<RpcRequest>();
        var codecs = new RpcCodecRegistry();
        CenterContractCodecs.register(codecs);
        RpcTransport transport = new CapturingTransport(captured);
        var options = new RpcCallOptions("run-reply", "default-trace", 5000);
        var client = new RpcClientFactory(transport, codecs, options, sender.assertion())
                .create(CenterContract.class);
        sender.asLogic("trace", () -> client.register(new CenterRegistration("logic", "logic", "trace")));
        var metadata = captured.get().securityMetadata();
        assertNotNull(metadata);
        var verified = receiver.verifier().verify(metadata, Instant.now()).toCompletableFuture().join();
        assertEquals("logic:run", verified.subject());
        assertEquals("trace", verified.traceId());
        assertEquals(captured.get().traceId(), verified.traceId());
        assertEquals("run-reply", captured.get().replyTopic());
        RpcCallContext.with(RpcCallContext.empty().withReplyTopic("nested-reply").withTimeoutMillis(7000), () -> {
            sender.asLogic("nested-trace", () -> client.register(new CenterRegistration("logic", "logic", "trace")));
            assertEquals("nested-reply", captured.get().replyTopic());
            assertEquals("nested-trace", captured.get().traceId());
            assertEquals(7000, RpcCallContext.current().resolveTimeoutMillis(options, 3000));
        });
        assertEquals("default-trace", RpcCallContext.current().resolveTraceId(options));
        assertTrue(SecurityContextBridge.current().isEmpty());
        assertNull(receiver.verifier().verify(metadata, metadata.expiresAt()).toCompletableFuture().join());
        assertNull(new CenterLogicSecurity("other-run", SECRET).verifier()
                .verify(metadata, Instant.now()).toCompletableFuture().join());
        client.register(new CenterRegistration("logic", "logic", "trace"));
        assertNull(captured.get().securityMetadata());
        assertNull(receiver.verifier().verify(null, Instant.now()).toCompletableFuture().join());
    }

    /** 错误签名、错误权限和缺配置均应明确失败。 */
    @Test void rejectsInvalidMaterialsSignatureAndPermissions() {
        assertThrows(IllegalArgumentException.class, () -> new CenterLogicSecurity("run", null));
        assertThrows(IllegalArgumentException.class, () -> new CenterLogicSecurity("run", "not-base64"));
        assertThrows(IllegalArgumentException.class, () -> new CenterLogicSecurity("run", "YQ=="));
        var receiver = new CenterLogicSecurity("run", SECRET);
        Instant now = Instant.now();
        var identity = new SecurityContext("logic:run", now, now.plusSeconds(60), "kafka", "peer", "peer",
                "trace", "corr", Set.of(), Map.of());
        var noPermission = SecurityMetadataAssertion.signed(identity, "test", receiver.assertion());
        assertNull(receiver.verifier().verify(noPermission, now).toCompletableFuture().join());
        var signed = receiver.asLogic("trace", () -> SecurityMetadataAssertion.signed(
                SecurityContextBridge.current().orElseThrow(), "test", receiver.assertion()));
        var tampered = new SecurityMetadataSnapshot(signed.subject(), signed.transport(), signed.peerAddress(),
                signed.trustedSourceAddress(), "changed-trace", signed.correlationId(), signed.permissions(),
                signed.assertionReference(), signed.issuedAt(), signed.expiresAt(), signed.signature());
        assertNull(receiver.verifier().verify(tampered, Instant.now()).toCompletableFuture().join());
    }

    /** 捕获真实 client 的请求，返回确定的拒绝响应；不连接外部服务。 @author zn */
    private record CapturingTransport(AtomicReference<RpcRequest> captured) implements RpcTransport {
        /** @return 测试提供者名；线程安全。 */
        @Override public String name() { return "capture"; }
        /** 捕获不可变请求。 @param request 请求。 @return 已完成的测试响应；线程安全。 */
        @Override public CompletionStage<RpcResponse> request(final RpcRequest request) {
            captured.set(request);
            return CompletableFuture.completedFuture(new RpcResponse(request.correlationId(), request.traceId(),
                    RpcErrorCode.SERVICE_NOT_FOUND, "capture only", new byte[0]));
        }
        /** 单向入口不属于本测试。 @param request 请求。 @return 失败阶段；线程安全。 */
        @Override public CompletionStage<Void> oneway(final RpcRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("request/response only"));
        }
    }
}
