package group.zn.zero.protocol;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import group.zn.zero.protocol.error.ProtocolErrorCode;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** 一个请求及其类型化异步响应的编码边界；不阻塞、不负责认证或 Actor 调度。 @author zn
 * @param <Q> 请求 DTO。 @param <R> 响应 DTO。 */
public final class AsyncProtocolEndpoint<Q, R> {
    /** 请求协议号。 */
    private final int requestId;
    /** 响应协议号。 */
    private final int responseId;
    /** 请求解码器。 */
    private final ZeroPayloadCodec<Q> requestCodec;
    /** 响应编码器。 */
    private final ZeroPayloadCodec<R> responseCodec;
    /** 线程安全异步业务端口。 */
    private final Function<Q, ? extends CompletionStage<R>> business;

    /** 创建无状态端点；业务与 codec 应可并发调用。
     * @param requestId 请求号。 @param requestCodec 请求 codec。 @param responseId 响应号。
     * @param responseCodec 响应 codec。 @param business 异步 BO。
     * @throws IllegalArgumentException 协议号非正。 @throws NullPointerException 必填参数为空。 */
    public AsyncProtocolEndpoint(final int requestId, final ZeroPayloadCodec<Q> requestCodec,
            final int responseId, final ZeroPayloadCodec<R> responseCodec,
            final Function<Q, ? extends CompletionStage<R>> business) {
        if (requestId <= 0 || responseId <= 0) throw new IllegalArgumentException("protocol IDs must be positive");
        this.requestId = requestId;
        this.responseId = responseId;
        this.requestCodec = Objects.requireNonNull(requestCodec);
        this.responseCodec = Objects.requireNonNull(responseCodec);
        this.business = Objects.requireNonNull(business);
    }

    /** 解码、异步执行业务、编码响应；沿用请求版本和关联扩展头，错误复用标准错误帧。
     * @param request 请求帧。 @return 实际业务完成后的响应；线程安全，无阻塞。
     * @throws NullPointerException 请求为空。 */
    public CompletionStage<ProtocolFrame> handle(final ProtocolFrame request) {
        Objects.requireNonNull(request, "request");
        CompletionStage<R> stage;
        try {
            if (request.protocolId() != requestId) throw ZeroException.of(ProtocolErrorCode.PROTOCOL_NOT_FOUND);
            var reader = request.payloadReader();
            Q dto = requestCodec.read(reader);
            if (reader.isReadable()) throw ZeroException.of(ProtocolErrorCode.DECODE_FAILED);
            stage = Objects.requireNonNull(business.apply(dto), "business stage");
        } catch (RuntimeException failure) { stage = CompletableFuture.failedFuture(failure); }
        return stage.handle((result, failure) -> {
            if (failure != null) return error(request, failure);
            try (var writer = new ZeroWriter()) {
                responseCodec.write(writer, Objects.requireNonNull(result, "business response"));
                return new ProtocolFrame(responseId, request.protocolVersion(), 0, request.extension(), writer.toByteArray());
            } catch (RuntimeException encodingFailure) { return error(request, encodingFailure); }
        });
    }

    private static ProtocolFrame error(final ProtocolFrame request, final Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
        String code = cause instanceof ZeroException zero ? zero.code() : SystemErrorCode.SYSTEM_ERROR.code();
        return ProtocolErrorFrames.response(request, code);
    }
}
