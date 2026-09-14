package group.zn.zero.security;

import java.util.Locale;
import java.util.Map;

final class SecurityValues {
    private SecurityValues() {
    }

    static void require(final String value, final String name, final int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    static Map<String, String> safeAttributes(final Map<String, String> source) {
        if (source == null) {
            throw new NullPointerException("attributes");
        }
        Map<String, String> copy = new java.util.LinkedHashMap<>();
        source.forEach((key, value) -> {
            require(key, "attribute key", 64);
            require(value, "attribute value", 256);
            if (sensitiveKey(key.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("sensitive security attribute is not allowed");
            }
            copy.put(key, value);
        });
        return Map.copyOf(copy);
    }

    private static boolean sensitiveKey(final String normalized) {
        return normalized.equals("token") || normalized.endsWith("token")
                || normalized.equals("password") || normalized.endsWith("password")
                || normalized.equals("secret") || normalized.endsWith("secret")
                || normalized.equals("key") || normalized.endsWith("key");
    }
}
