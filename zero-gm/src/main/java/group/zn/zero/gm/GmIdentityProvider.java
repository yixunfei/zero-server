package group.zn.zero.gm;

import java.util.Objects;
import java.util.function.Function;

/** Application-supplied identity resolution boundary for a GM transport. */
@FunctionalInterface
public interface GmIdentityProvider {

    /** Resolves an authenticated command context from transport metadata and request headers. */
    GmCommandContext resolve(GmTransportRequest request);

    /** Creates a fail-closed provider that rejects every request. */
    static GmIdentityProvider failClosed() {
        return request -> {
            throw new IllegalArgumentException("GM identity is required");
        };
    }

    /** Adapts a trusted principal resolver while retaining request metadata. */
    static GmIdentityProvider from(final Function<GmTransportRequest, GmCommandContext> resolver) {
        Objects.requireNonNull(resolver, "resolver");
        return resolver::apply;
    }
}
