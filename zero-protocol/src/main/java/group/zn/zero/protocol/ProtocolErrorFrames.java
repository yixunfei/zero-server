package group.zn.zero.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 通用错误响应契约；只携带稳定错误码，关联原请求协议，禁止透传异常文本。 @author zn */
public final class ProtocolErrorFrames {
    private ProtocolErrorFrames() { }

    /**
     * 为请求构造独立 ERROR 响应；新负载不继承原请求压缩、加密或签名位。
     * @param request 原请求；不可为空。
     * @param code 稳定 ErrorCode 字符串；大写字母、数字和横线，最多 128 字符。
     * @return 不可变、线程安全的错误帧；不修改请求。
     * @throws IllegalArgumentException 错误码非法。
     * @throws NullPointerException 请求或错误码为空。
     */
    public static ProtocolFrame response(final ProtocolFrame request, final String code) {
        Objects.requireNonNull(request, "request");
        if (!Objects.requireNonNull(code, "code").matches("[A-Z0-9-]{1,128}")) {
            throw new IllegalArgumentException("error frame requires a stable error code");
        }
        return new ProtocolFrame(request.protocolId(), request.protocolVersion(), ProtocolFeature.ERROR.mask(),
                null, ("error|protocol=" + request.protocolId() + "|reason=" + code).getBytes(StandardCharsets.UTF_8));
    }
}
