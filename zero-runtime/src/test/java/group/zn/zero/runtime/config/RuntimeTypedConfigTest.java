package group.zn.zero.runtime.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.assembly.ComponentCatalog;
import group.zn.zero.runtime.assembly.RuntimeAssembler;
import group.zn.zero.runtime.assembly.RuntimeProfile;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyException;
import group.zn.zero.runtime.diagnostics.RuntimeErrorCode;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.support.FakeRuntimeProvider;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * typed schema、来源优先级和配置脱敏测试。
 */
class RuntimeTypedConfigTest {

    private static final ComponentId SETTINGS_ID = ComponentId.of("config.provider.settings");
    private static final ComponentKey<Settings> SETTINGS =
            ComponentKey.single("config.settings", Settings.class);
    private static final ConfigKey<Integer> PORT = ConfigKey
            .integer(SETTINGS_ID, "config.settings.port")
            .validate(value -> value > 0 && value < 65_536, "valid-port")
            .build();
    private static final ConfigKey<Duration> TIMEOUT = ConfigKey
            .duration(SETTINGS_ID, "config.settings.timeout")
            .defaultValue(Duration.ofSeconds(5))
            .validate(value -> !value.isZero() && !value.isNegative(), "positive-duration")
            .build();
    private static final ConfigKey<String> LABEL = ConfigKey
            .string(SETTINGS_ID, "config.settings.label")
            .optional()
            .build();
    private static final ConfigKey<String> SECRET = ConfigKey
            .string(SETTINGS_ID, "config.settings.secret")
            .sensitive()
            .alias(ConfigSourceKind.ENVIRONMENT, "ZERO_TEST_SECRET")
            .build();

    @Test
    void higherPrioritySourceShouldWinAndDefaultsShouldResolve() {
        AtomicReference<String> observedSecret = new AtomicReference<>();
        FakeRuntimeProvider provider = settingsProvider(observedSecret);
        MapConfigSource high = new MapConfigSource(
                ConfigSourceKind.PROGRAMMATIC,
                "programmatic-test",
                Map.of(PORT.logicalName(), "7001", SECRET.logicalName(), "sentinel-secret"));
        MapConfigSource low = new MapConfigSource(
                ConfigSourceKind.FILE,
                "file-test",
                Map.of(PORT.logicalName(), "9001", LABEL.logicalName(), "ignored-label"));

        var runtime = assembler(provider)
                .configSource(high)
                .configSource(low)
                .build();
        Settings settings = runtime.require(SETTINGS);

        assertEquals(7001, settings.port());
        assertEquals(Duration.ofSeconds(5), settings.timeout());
        assertEquals("ignored-label", settings.label());
        assertEquals("sentinel-secret", observedSecret.get());
        assertTrue(runtime.plan().config().stream()
                .anyMatch(metadata -> metadata.logicalKey().equals(SECRET.logicalName()) && metadata.sensitive()));
        assertFalse(runtime.plan().toString().contains("sentinel-secret"));
        assertFalse(runtime.report().toString().contains("sentinel-secret"));
        assertFalse(high.toString().contains("sentinel-secret"));
        runtime.close();
    }

    @Test
    void invalidHighPriorityValueShouldNotFallBackToLowerSource() {
        FakeRuntimeProvider provider = settingsProvider(new AtomicReference<>());
        String sentinel = "invalid-secret-value";
        MapConfigSource high = new MapConfigSource(
                ConfigSourceKind.PROGRAMMATIC,
                "programmatic-test",
                Map.of(PORT.logicalName(), sentinel, SECRET.logicalName(), "safe"));
        MapConfigSource low = new MapConfigSource(
                ConfigSourceKind.FILE,
                "file-test",
                Map.of(PORT.logicalName(), "7001"));

        RuntimeAssemblyException failure = assertThrows(
                RuntimeAssemblyException.class,
                () -> assembler(provider).configSource(high).configSource(low).build());

        assertEquals(RuntimeErrorCode.RUNTIME_CONFIG_INVALID, failure.errorCode());
        assertFalse(failure.toString().contains(sentinel));
        assertNull(failure.getCause());
        assertEquals(0, provider.createCount());
    }

    @Test
    void missingRequiredKeysShouldBeAggregatedBeforeCreate() {
        FakeRuntimeProvider provider = settingsProvider(new AtomicReference<>());

        RuntimeAssemblyException failure = assertThrows(
                RuntimeAssemblyException.class,
                () -> assembler(provider).build());

        assertEquals(RuntimeErrorCode.RUNTIME_CONFIG_MISSING, failure.errorCode());
        assertTrue(failure.getMessage().contains(PORT.logicalName()));
        assertTrue(failure.getMessage().contains(SECRET.logicalName()));
        assertEquals(0, provider.createCount());
    }

    @Test
    void disallowedSourceShouldFailWithoutFallback() {
        ConfigKey<String> programmaticOnly = ConfigKey
                .string(SETTINGS_ID, "config.settings.programmatic-only")
                .acceptedSources(Set.of(ConfigSourceKind.PROGRAMMATIC))
                .build();
        ConfigSchema schema = ConfigSchema.builder(SETTINGS_ID).add(programmaticOnly).build();
        FakeRuntimeProvider provider = providerForSchema(schema, context -> {
            context.config().require(programmaticOnly);
            return new Settings(1, Duration.ofSeconds(1), "none");
        });
        MapConfigSource environment = new MapConfigSource(
                ConfigSourceKind.ENVIRONMENT,
                "environment-test",
                Map.of(programmaticOnly.logicalName(), "value"));

        RuntimeAssemblyException failure = assertThrows(
                RuntimeAssemblyException.class,
                () -> assembler(provider).configSource(environment).build());

        assertEquals(RuntimeErrorCode.RUNTIME_CONFIG_INVALID, failure.errorCode());
        assertEquals(0, provider.createCount());
    }

    @Test
    void zeroConfigSourceShouldBridgeExistingStringConfigExplicitly() {
        AtomicReference<String> observedSecret = new AtomicReference<>();
        FakeRuntimeProvider provider = settingsProvider(observedSecret);
        ZeroConfigSource source = new ZeroConfigSource(
                "zero-config-test",
                new MapZeroConfig(Map.of(PORT.logicalName(), "8123", SECRET.logicalName(), "bridge-secret")));
        var runtime = assembler(provider).configSource(source).build();

        assertEquals(8123, runtime.require(SETTINGS).port());
        assertEquals("bridge-secret", observedSecret.get());
        assertFalse(source.toString().contains("bridge-secret"));
        runtime.close();
    }

    @Test
    void duplicateLogicalKeyAcrossCatalogShouldFailAtFreeze() {
        ComponentId otherId = ComponentId.of("config.provider.other");
        ComponentKey<String> otherBinding = ComponentKey.single("config.other", String.class);
        ConfigKey<String> duplicate = ConfigKey
                .string(otherId, PORT.logicalName())
                .build();
        FakeRuntimeProvider first = settingsProvider(new AtomicReference<>());
        ComponentDescriptor otherDescriptor = ComponentDescriptor.builder(otherId)
                .provide(otherBinding)
                .configSchema(ConfigSchema.builder(otherId).add(duplicate).build())
                .build();
        FakeRuntimeProvider other = new FakeRuntimeProvider(otherDescriptor, context -> ComponentContribution.builder()
                .bind(otherBinding, "other")
                .build());

        RuntimeAssemblyException failure = assertThrows(
                RuntimeAssemblyException.class,
                () -> ComponentCatalog.builder()
                        .register("config-test", first)
                        .register("config-test", other)
                        .build());

        assertEquals(RuntimeErrorCode.RUNTIME_DUPLICATE_CONFIG_KEY, failure.errorCode());
    }

    private static FakeRuntimeProvider settingsProvider(final AtomicReference<String> observedSecret) {
        ConfigSchema schema = ConfigSchema.builder(SETTINGS_ID)
                .add(PORT)
                .add(TIMEOUT)
                .add(LABEL)
                .add(SECRET)
                .build();
        return providerForSchema(schema, context -> {
            observedSecret.set(context.config().require(SECRET));
            return new Settings(
                    context.config().require(PORT),
                    context.config().require(TIMEOUT),
                    context.config().optional(LABEL).orElse(null));
        });
    }

    private static FakeRuntimeProvider providerForSchema(
            final ConfigSchema schema,
            final SettingsFactory factory) {
        ComponentDescriptor descriptor = ComponentDescriptor.builder(SETTINGS_ID)
                .provide(SETTINGS)
                .configSchema(schema)
                .build();
        return new FakeRuntimeProvider(descriptor, context -> ComponentContribution.builder()
                .bind(SETTINGS, factory.create(context))
                .build());
    }

    private static RuntimeAssembler.Builder assembler(final FakeRuntimeProvider provider) {
        ComponentCatalog catalog = ComponentCatalog.builder()
                .register("config-test", provider)
                .build();
        return RuntimeAssembler.builder(catalog, RuntimeProfile.local())
                .require(SETTINGS)
                .select(SETTINGS, SETTINGS_ID, "config-test");
    }

    private record Settings(int port, Duration timeout, String label) {
    }

    @FunctionalInterface
    private interface SettingsFactory {
        Settings create(group.zn.zero.runtime.spi.ComponentCreationContext context);
    }
}
