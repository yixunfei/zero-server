package group.zn.zero.net.http;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * 最小 HTTP 响应。
 *
 * @param status HTTP 状态码。
 * @param headers 响应头快照。
 * @param body 响应体字节。
 * @author zn
 */
public record HttpResponse(
        int status,
        Map<String, String> headers,
        byte[] body) {

    /**
     * 创建 HTTP 响应。
     *
     * @throws NullPointerException 当响应头或响应体为空时抛出。
     * @throws IllegalArgumentException 当状态码非法时抛出。
     */
    public HttpResponse {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be valid HTTP status code");
        }
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        body = Objects.requireNonNull(body, "body").clone();
    }

    /**
     * 创建文本响应。
     *
     * @param status HTTP 状态码。
     * @param text 文本内容；不可为空。
     * @return HTTP 响应；不可为空；线程安全。
     */
    public static HttpResponse text(final int status, final String text) {
        return new HttpResponse(
                status,
                Map.of("content-type", "text/plain; charset=utf-8"),
                Objects.requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 返回响应体副本。
     *
     * @return 响应体字节；不可为空；有序；可能为空；线程安全。
     */
    @Override
    public byte[] body() {
        return body.clone();
    }
}
