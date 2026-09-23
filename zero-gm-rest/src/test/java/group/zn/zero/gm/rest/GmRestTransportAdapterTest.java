package group.zn.zero.gm.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import group.zn.zero.gm.GmCommandContext;
import group.zn.zero.gm.GmIdentityProvider;
import group.zn.zero.gm.GmOperationResponse;
import group.zn.zero.gm.GmTransportAdapter;
import group.zn.zero.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GmRestTransportAdapterTest {
    private static HttpRequest request(final String body) {
        return new HttpRequest("POST", "/gm/operation", Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsMalformedAndOversizedBodiesWithoutCallingDelegate() {
        int[] calls = {0};
        GmRestTransportAdapter adapter = new GmRestTransportAdapter(
                (operation, metadata) -> { calls[0]++; return GmOperationResponse.success(null, metadata.traceId()); },
                GmIdentityProvider.failClosed(), 8);
        assertEquals(413, adapter.handle(request("123456789")).status());
        assertEquals(400, adapter.handle(request("not-json")).status());
        assertEquals(0, calls[0]);
    }

    @Test
    void missingIdentityFailsClosed() {
        GmRestTransportAdapter adapter = GmRestTransportAdapter.failClosed(
                (operation, metadata) -> GmOperationResponse.success(null, metadata.traceId()));
        HttpRequest request = request("{\"traceId\":\"t\",\"correlationId\":\"c\",\"idempotencyKey\":\"i\",\"command\":\"ping\",\"target\":\"server\"}");
        assertEquals(400, adapter.handle(request).status());
    }

    @Test
    void validRequestUsesApplicationIdentityAndBoundedMetadata() {
        GmCommandContext context = GmCommandContext.simple("alice", "192.0.2.1", "t");
        GmRestTransportAdapter adapter = new GmRestTransportAdapter(
                (operation, metadata) -> {
                    assertFalse(!metadata.authenticated());
                    assertEquals("t", metadata.traceId());
                    return GmOperationResponse.success(null, metadata.traceId());
                }, GmIdentityProvider.from(request -> context), 1024);
        HttpRequest request = request("{\"traceId\":\"t\",\"correlationId\":\"c\",\"idempotencyKey\":\"i\",\"command\":\"ping\",\"target\":\"server\"}");
        assertEquals(200, adapter.handle(request).status());
    }
}
