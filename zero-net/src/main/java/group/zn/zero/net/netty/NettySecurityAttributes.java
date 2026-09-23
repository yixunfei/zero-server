package group.zn.zero.net.netty;

import group.zn.zero.security.SecurityContext;
import io.netty.util.AttributeKey;

/** Internal HTTP transport attribute keys; applications must explicitly populate them. */
final class NettySecurityAttributes {
    static final AttributeKey<SecurityContext> CONTEXT =
            AttributeKey.valueOf("zero.net.http.security.context");

    private NettySecurityAttributes() {
    }
}
