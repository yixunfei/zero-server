package group.zn.zero.runtime.net;

import group.zn.zero.net.lifecycle.ProductionNetworkConfig;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.config.ConfigSource;
import group.zn.zero.runtime.config.ConfigSourceKind;
import group.zn.zero.runtime.config.MapConfigSource;
import group.zn.zero.runtime.production.ProductionAdapterNames;
import group.zn.zero.runtime.production.ProductionConfigResolver;
import group.zn.zero.runtime.production.ResolvedProductionSetting;
import group.zn.zero.runtime.production.ZeroProductionRuntimeConfigKeys;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 保留既有 network 配置语义并桥接到 runtime typed config 的包内解析结果。 */
record ProductionNetworkSettings(List<ConfigSource> configSources) {

    private static final String TYPED_ALIAS_PREFIX = "resolved.";

    ProductionNetworkSettings {
        configSources = List.copyOf(Objects.requireNonNull(configSources, "configSources"));
    }

    static ProductionNetworkSettings resolve(final ProductionConfigResolver resolver) {
        ProductionConfigResolver checked = Objects.requireNonNull(resolver, "resolver");
        List<ResolvedProductionSetting> settings = new ArrayList<>();
        ResolvedProductionSetting listener = readDefaultable(
                checked, ZeroProductionRuntimeConfigKeys.NETWORK_LISTENER);
        ResolvedProductionSetting handshake = readDefaultable(
                checked, ZeroProductionRuntimeConfigKeys.NETWORK_HANDSHAKE_TIMEOUT_MILLIS);
        ResolvedProductionSetting authentication = readDefaultable(
                checked, ZeroProductionRuntimeConfigKeys.NETWORK_AUTHENTICATION_TIMEOUT_MILLIS);
        ResolvedProductionSetting heartbeat = readDefaultable(
                checked, ZeroProductionRuntimeConfigKeys.NETWORK_HEARTBEAT_INTERVAL_MILLIS);
        ResolvedProductionSetting heartbeatEnabled = readDefaultable(
                checked, ZeroProductionRuntimeConfigKeys.NETWORK_HEARTBEAT_ENABLED);
        ResolvedProductionSetting missed = readDefaultable(
                checked, ZeroProductionRuntimeConfigKeys.NETWORK_ALLOWED_MISSED_HEARTBEATS);
        ResolvedProductionSetting reconnect = readDefaultable(
                checked, ZeroProductionRuntimeConfigKeys.NETWORK_RECONNECT_WINDOW_MILLIS);
        ResolvedProductionSetting inbound = readDefaultable(
                checked, ZeroProductionRuntimeConfigKeys.NETWORK_MAX_INBOUND_FRAMES);
        settings.addAll(List.of(listener, handshake, authentication, heartbeat, heartbeatEnabled,
                missed, reconnect, inbound));

        networkConfig(
                listener.orElse(ZeroProductionRuntimeConfigKeys.DEFAULT_NETWORK_LISTENER),
                positiveInt(handshake, millis(ProductionNetworkConfig.DEFAULT_HANDSHAKE_TIMEOUT)),
                positiveInt(authentication, millis(ProductionNetworkConfig.DEFAULT_AUTHENTICATION_TIMEOUT)),
                positiveInt(heartbeat, millis(ProductionNetworkConfig.DEFAULT_HEARTBEAT_INTERVAL)),
                booleanValue(heartbeatEnabled),
                positiveInt(missed, ProductionNetworkConfig.DEFAULT_ALLOWED_MISSED_HEARTBEATS),
                positiveInt(reconnect, millis(ProductionNetworkConfig.DEFAULT_RECONNECT_WINDOW)),
                positiveInt(inbound, ProductionNetworkConfig.DEFAULT_MAX_INBOUND_FRAMES));

        return new ProductionNetworkSettings(configSources(ProductionNetworkProvider.ID, settings));
    }

    static String typedAlias(final String logicalKey) {
        return TYPED_ALIAS_PREFIX + Objects.requireNonNull(logicalKey, "logicalKey");
    }

    private static boolean booleanValue(final ResolvedProductionSetting setting) {
        String value = setting.orElse("false");
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new InvalidSettingException(setting.logicalKey(),
                    new IllegalArgumentException("expected boolean"));
        }
        return Boolean.parseBoolean(value);
    }

    private static ProductionNetworkConfig networkConfig(
            final String listener,
            final int handshakeMillis,
            final int authenticationMillis,
            final int heartbeatMillis,
            final boolean heartbeatEnabled,
            final int allowedMissedHeartbeats,
            final int reconnectMillis,
            final int maxInboundFrames) {
        try {
            return new ProductionNetworkConfig(
                    listener,
                    Duration.ofMillis(handshakeMillis),
                    Duration.ofMillis(authenticationMillis),
                    Duration.ofMillis(heartbeatMillis),
                    allowedMissedHeartbeats,
                    Duration.ofMillis(reconnectMillis),
                    maxInboundFrames,
                    false,
                    heartbeatEnabled);
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw new InvalidSettingException(
                    ZeroProductionRuntimeConfigKeys.NETWORK_LISTENER, failure);
        }
    }

    private static ResolvedProductionSetting readDefaultable(
            final ProductionConfigResolver resolver,
            final String key) {
        return resolver.readDefaultable(
                ProductionAdapterNames.ADAPTER_NETWORK_LIFECYCLE,
                key,
                false,
                List.of(key),
                List.of(),
                List.of());
    }

    private static int positiveInt(
            final ResolvedProductionSetting setting,
            final int defaultValue) {
        try {
            int parsed = Integer.parseInt(setting.orElse(Integer.toString(defaultValue)));
            if (parsed <= 0) {
                throw new IllegalArgumentException("network setting must be positive");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new InvalidSettingException(setting.logicalKey(), failure);
        } catch (IllegalArgumentException failure) {
            throw new InvalidSettingException(setting.logicalKey(), failure);
        }
    }

    private static List<ConfigSource> configSources(
            final ComponentId owner,
            final List<ResolvedProductionSetting> settings) {
        Map<String, String> values = new LinkedHashMap<>();
        settings.stream()
                .filter(ResolvedProductionSetting::present)
                .forEach(setting -> values.put(typedAlias(setting.logicalKey()), setting.require()));
        if (values.isEmpty()) {
            return List.of();
        }
        return List.of(new MapConfigSource(
                ConfigSourceKind.PROGRAMMATIC,
                owner.value() + ".programmatic",
                values));
    }

    private static int millis(final Duration duration) {
        return Math.toIntExact(Objects.requireNonNull(duration, "duration").toMillis());
    }

    /** 安全携带非法逻辑键而不暴露配置值。 */
    static final class InvalidSettingException extends IllegalArgumentException {

        private final String logicalKey;

        InvalidSettingException(final String logicalKey, final Throwable cause) {
            super("invalid production network setting", cause);
            this.logicalKey = Objects.requireNonNull(logicalKey, "logicalKey");
        }

        String logicalKey() {
            return logicalKey;
        }
    }
}
