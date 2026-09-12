package group.zn.zero.gm;

import java.util.Objects;

/** Common adapter boundary for trusted HTTP/RPC GM transports. */
@FunctionalInterface
public interface GmTransportAdapter {
    /** Handles one already bounded and authenticated operation request. */
    GmOperationResponse handle(GmOperationRequest request, GmTransportMetadata metadata);

    /** Rejects unauthenticated transport metadata before the GM endpoint is reached. */
    static void requireAuthenticated(final GmTransportMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        if (!metadata.authenticated()) {
            throw new IllegalArgumentException("GM transport authentication is required");
        }
    }
}
