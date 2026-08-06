package group.zn.zero.starter.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.lifecycle.NetworkAdmissionDecision;
import group.zn.zero.net.lifecycle.ProductionNetworkConfig;
import group.zn.zero.starter.ZeroRuntimeComponents;
import group.zn.zero.starter.ZeroRuntimeFactory;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * production 网络生命周期装配工厂测试。
 *
 * @author zn
 */
class ZeroProductionNetworkFactoryTest {

    /**
     * 验证 production 网络生命周期默认关闭，未启用时解析会绑定统一参数错误码。
     */
    @Test
    void lifecycleShouldRemainDisabledByDefault() {
        MapZeroConfig config = new MapZeroConfig(Map.of());

        assertFalse(ZeroProductionNetworkFactory.enabled(config));
        ZeroException exception = assertThrows(
                ZeroException.class,
                () -> ZeroProductionNetworkFactory.resolveConfig(config));
        assertEquals(SystemErrorCode.INVALID_ARGUMENT.code(), exception.code());
    }

    /**
     * 验证显式启用后会解析用户确认的首轮默认值。
     */
    @Test
    void enabledLifecycleShouldResolveConfirmedDefaults() {
        MapZeroConfig config = new MapZeroConfig(Map.of(
                ZeroProductionRuntimeConfigKeys.NETWORK_LIFECYCLE_ENABLED,
                "true"));

        ProductionNetworkConfig resolved = ZeroProductionNetworkFactory.resolveConfig(config);

        assertTrue(ZeroProductionNetworkFactory.enabled(config));
        assertEquals(ProductionNetworkConfig.DEFAULT_HANDSHAKE_TIMEOUT, resolved.handshakeTimeout());
        assertEquals(ProductionNetworkConfig.DEFAULT_AUTHENTICATION_TIMEOUT, resolved.authenticationTimeout());
        assertEquals(ProductionNetworkConfig.DEFAULT_HEARTBEAT_INTERVAL, resolved.heartbeatInterval());
        assertEquals(ProductionNetworkConfig.DEFAULT_ALLOWED_MISSED_HEARTBEATS, resolved.allowedMissedHeartbeats());
        assertEquals(ProductionNetworkConfig.DEFAULT_RECONNECT_WINDOW, resolved.reconnectWindow());
        assertEquals(ProductionNetworkConfig.DEFAULT_MAX_INBOUND_FRAMES, resolved.maxInboundFrames());
    }

    /**
     * 验证鉴权不会误用可能在 Netty IO 调用栈内联执行的本地默认执行器。
     */
    @Test
    void lifecycleShouldRejectInlineRemoteIoExecutor() {
        MapZeroConfig config = new MapZeroConfig(Map.of(
                ZeroProductionRuntimeConfigKeys.NETWORK_LIFECYCLE_ENABLED,
                "true"));
        ZeroRuntimeComponents components = ZeroRuntimeFactory.localDefault(config);

        ZeroException exception = assertThrows(
                ZeroException.class,
                () -> ZeroProductionNetworkFactory.create(
                        config,
                        (connection, frame) -> NetworkAdmissionDecision.allow(),
                        components));
        assertEquals(SystemErrorCode.INVALID_ARGUMENT.code(), exception.code());
    }
}
