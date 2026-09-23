package group.zn.zero.net.http;

import group.zn.zero.security.SecurityMetadataHttpCodec;
import group.zn.zero.security.SecurityMetadataSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal HTTP request.
 *
 * @param method HTTP method.
 * @param uri request URI.
 * @param headers request header snapshot.
 * @param body request body bytes.
 * @author zn
 */
public record HttpRequest(
        String method,
        String uri,
        Map<String, String> headers,
        byte[] body) {

    /** Creates an HTTP request. */
    public HttpRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(uri, "uri");
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        body = Objects.requireNonNull(body, "body").clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    /** Returns the UTF-8 text body. */
    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** Returns a copy with an explicit security metadata header. */
    public HttpRequest withSecurityMetadata(final String encodedMetadata) {
        Map<String, String> updated = new java.util.LinkedHashMap<>(headers);
        updated.put(SecurityMetadataHttpCodec.HEADER, Objects.requireNonNull(encodedMetadata, "encodedMetadata"));
        return new HttpRequest(method, uri, updated, body);
    }
}
