package group.zn.zero.gm;

import java.util.Map;
import java.util.Objects;

/** Bounded, transport-neutral request presented to a GM identity provider. */
public record GmTransportRequest(
        String method,
        String path,
        Map<String, String> headers,
        String body) {
    public GmTransportRequest {
        method = required(method, "method");
        path = required(path, "path");
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        body = Objects.requireNonNull(body, "body");
    }

    private static String required(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 128 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
