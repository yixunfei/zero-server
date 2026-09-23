package group.zn.zero.gm;

import java.util.Objects;

/** Simple adapter facade that enforces trusted metadata before delegating to the core endpoint. */
public final class BoundedGmTransportAdapter implements GmTransportAdapter {
    private final GmOperationEndpoint endpoint;

    /** Creates a transport facade around a transport-neutral endpoint. */
    public BoundedGmTransportAdapter(final GmOperationEndpoint endpoint) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    }

    /** Rejects unauthenticated metadata and delegates authenticated requests. */
    @Override
    public GmOperationResponse handle(final GmOperationRequest request, final GmTransportMetadata metadata) {
        GmTransportAdapter.requireAuthenticated(metadata);
        Objects.requireNonNull(request, "request");
        if (!metadata.traceId().equals(request.context().traceId())
                || !metadata.trustedSourceIp().equals(request.context().operatorIp())) {
            throw new IllegalArgumentException("GM transport metadata does not match context");
        }
        return endpoint.handle(request);
    }
}
