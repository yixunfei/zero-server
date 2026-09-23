package group.zn.zero.net.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import group.zn.zero.net.lifecycle.NetworkAdmissionDecision;
import group.zn.zero.net.lifecycle.ProductionNetworkConfig;
import group.zn.zero.net.lifecycle.ProductionNetworkConnectionAttributes;
import group.zn.zero.net.lifecycle.ProductionNetworkLifecycle;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

/** TLS 属性的 Optional 必须按值判断。 @author zn */
class TlsReportAuditTest {
    /** 已完成 TLS 的连接不应因 Optional 与 Boolean 比较被拒绝。 */
    @Test void tlsRequirementAcceptsOnlyEstablishedTls() {
        for (boolean established : new boolean[] {false, true}) {
            EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
            NettyConnection connection = new NettyConnection("test", channel);
            connection.attributes().put(ProductionNetworkConnectionAttributes.TLS_ESTABLISHED, established);
            ProductionNetworkLifecycle lifecycle = new ProductionNetworkLifecycle(
                    ProductionNetworkConfig.defaults("test").withTlsRequired(true),
                    (current, frame) -> NetworkAdmissionDecision.allow(), Runnable::run);
            NettyProductionLifecycleSession session = new NettyProductionLifecycleSession(
                    channel.pipeline().firstContext(), connection, lifecycle, frame -> { }, () -> { },
                    failure -> { throw new AssertionError(failure); });
            try {
                session.start();
                assertEquals(established, channel.isActive());
            } finally {
                session.onChannelInactive();
                channel.finishAndReleaseAll();
            }
        }
    }
}
