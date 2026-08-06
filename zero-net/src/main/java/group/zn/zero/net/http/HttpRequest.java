package group.zn.zero.net.http;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * 最小 HTTP 请求。
 *
 * @param method HTTP 方法。
 * @param uri 请求 URI。
 * @param headers 请求头快照。
 * @param body 请求体字节。
 * @author zn
 */
public record HttpRequest(
        String method,
        String uri,
        Map<String, String> headers,
        byte[] body) {

    /**
     * 创建 HTTP 请求。
     *
     * @throws NullPointerException 当方法、URI、请求头或请求体为空时抛出。
     */
    public HttpRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(uri, "uri");
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        body = Objects.requireNonNull(body, "body").clone();
    }

    /**
     * 返回请求体副本。
     *
     * @return 请求体字节；不可为空；有序；可能为空；线程安全。
     */
    @Override
    public byte[] body() {
        return body.clone();
    }

    /**
     * 返回 UTF-8 文本请求体。
     *
     * @return 文本请求体；不可为空；线程安全。
     */
    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
