package group.zn.zero.runtime.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.lifecycle.Lifecycle;
import group.zn.zero.core.lifecycle.LifecycleState;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.config.ConfigKey;
import group.zn.zero.runtime.config.ConfigSchema;
import group.zn.zero.runtime.config.ConfigSource;
import group.zn.zero.runtime.config.ConfigSourceKind;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyException;
import group.zn.zero.runtime.diagnostics.RuntimeErrorCode;
import group.zn.zero.runtime.diagnostics.RuntimeFailurePhase;
import group.zn.zero.runtime.health.HealthPhase;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentCreationContext;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import group.zn.zero.runtime.support.FakeRuntimeProvider;
import group.zn.zero.runtime.support.RecordingResource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * raw provider、probe、resource 和配置来源消息的脱敏边界测试。
 */
class RuntimeSecurityBoundaryTest {

    private static final String SECRET = "unique-runtime-secret-91f14";
    private static final ComponentId PROVIDER_ID = ComponentId.of("security.provider.primary");
    private static final ComponentKey<String> VALUE = ComponentKey.single("security.value", String.class);

    @Test
    void createAndRollbackFailuresShouldExposeOnlySafeExceptions() {
        List<String> events = new ArrayList<>();
        ComponentDescriptor descriptor = ComponentDescriptor.builder(PROVIDER_ID).provide(VALUE).build();
        FakeRuntimeProvider provider = new FakeRuntimeProvider(descriptor, context -> {
            context.resources().register(new RecordingResource("primary", events, 1, SECRET));
            throw new IllegalStateException(SECRET);
        });

        RuntimeAssemblyException failure = assertThrows(
                RuntimeAssemblyException.class,
                () -> assembler(provider).build());

        assertEquals(RuntimeErrorCode.RUNTIME_COMPONENT_CREATE_FAILED, failure.errorCode());
        assertEquals(1, failure.getSuppressed().length);
        assertNoSecret(failure);
        assertFalse(failure.report().orElseThrow().toString().contains(SECRET));
    }

    @Test
    void providerSuppliedAssemblyExceptionShouldBeRenormalized() {
        ComponentDescriptor descriptor = ComponentDescriptor.builder(PROVIDER_ID).provide(VALUE).build();
        FakeRuntimeProvider provider = new FakeRuntimeProvider(descriptor, context -> {
            throw new RuntimeAssemblyException(
                    RuntimeErrorCode.RUNTIME_COMPONENT_CREATE_FAILED,
                    RuntimeFailurePhase.CREATE,
                    PROVIDER_ID,
                    SECRET);
        });

        RuntimeAssemblyException failure = assertThrows(
                RuntimeAssemblyException.class,
                () -> assembler(provider).build());

        assertEquals(RuntimeErrorCode.RUNTIME_COMPONENT_CREATE_FAILED, failure.errorCode());
        assertNoSecret(failure);
    }

    @Test
    void lifecycleSuppliedAssemblyExceptionShouldBeRenormalized() {
        ComponentDescriptor descriptor = ComponentDescriptor.builder(PROVIDER_ID).provide(VALUE).build();
        FakeRuntimeProvider provider = new FakeRuntimeProvider(descriptor, context -> ComponentContribution.builder()
                .bind(VALUE, "value")
                .lifecycle(unsafeLifecycle())
                .build());
        var runtime = assembler(provider).build();

        RuntimeAssemblyException failure = assertThrows(RuntimeAssemblyException.class, runtime::start);

        assertEquals(RuntimeErrorCode.RUNTIME_COMPONENT_START_FAILED, failure.errorCode());
        assertNoSecret(failure);
    }

    @Test
    void exceptionalHealthFutureShouldNotLeakRawCause() {
        ComponentDescriptor descriptor = ComponentDescriptor.builder(PROVIDER_ID)
                .provide(VALUE)
                .health(HealthPhase.STARTUP)
                .build();
        FakeRuntimeProvider provider = new FakeRuntimeProvider(descriptor, context -> ComponentContribution.builder()
                .bind(VALUE, "value")
                .healthProbe(HealthPhase.STARTUP, request -> CompletableFuture.failedFuture(
                        new IllegalStateException(SECRET)))
                .build());
        var runtime = assembler(provider).build();

        RuntimeAssemblyException failure = assertThrows(RuntimeAssemblyException.class, runtime::start);

        assertEquals(RuntimeErrorCode.RUNTIME_COMPONENT_HEALTH_FAILED, failure.errorCode());
        assertNoSecret(failure);
        assertFalse(runtime.report().toString().contains(SECRET));
    }

    @Test
    void unsafeCustomSourceMetadataShouldFailWithoutEchoingPath() {
        ConfigKey<String> key = ConfigKey.string(PROVIDER_ID, "security.config.secret").sensitive().build();
        ConfigSchema schema = ConfigSchema.builder(PROVIDER_ID).add(key).build();
        ComponentDescriptor descriptor = ComponentDescriptor.builder(PROVIDER_ID)
                .provide(VALUE)
                .configSchema(schema)
                .build();
        FakeRuntimeProvider provider = new FakeRuntimeProvider(descriptor, context -> ComponentContribution.builder()
                .bind(VALUE, context.config().require(key))
                .build());
        ConfigSource unsafe = new ConfigSource() {
            @Override
            public ConfigSourceKind kind() {
                return ConfigSourceKind.FILE;
            }

            @Override
            public String id() {
                return "C:/credentials/" + SECRET;
            }

            @Override
            public Optional<String> value(final String alias) {
                return Optional.of(SECRET);
            }
        };

        RuntimeAssemblyException failure = assertThrows(
                RuntimeAssemblyException.class,
                () -> assembler(provider).configSource(unsafe).build());

        assertEquals(RuntimeErrorCode.RUNTIME_CONFIG_INVALID, failure.errorCode());
        assertNoSecret(failure);
        assertEquals(0, provider.createCount());
    }

    @Test
    void configSourceErrorShouldNotLeakRawMessage() {
        ConfigKey<String> key = ConfigKey.string(PROVIDER_ID, "security.config.error").build();
        ConfigSchema schema = ConfigSchema.builder(PROVIDER_ID).add(key).build();
        ComponentDescriptor descriptor = ComponentDescriptor.builder(PROVIDER_ID)
                .provide(VALUE)
                .configSchema(schema)
                .build();
        FakeRuntimeProvider provider = new FakeRuntimeProvider(descriptor, context -> ComponentContribution.builder()
                .bind(VALUE, context.config().require(key))
                .build());
        ConfigSource unsafe = errorConfigSource();

        RuntimeAssemblyException failure = assertThrows(
                RuntimeAssemblyException.class,
                () -> assembler(provider).configSource(unsafe).build());

        assertEquals(RuntimeErrorCode.RUNTIME_CONFIG_INVALID, failure.errorCode());
        assertNoSecret(failure);
        assertEquals(0, provider.createCount());
    }

    @Test
    void descriptorFailureShouldNotLeakProviderMessage() {
        RuntimeComponentProvider provider = new RuntimeComponentProvider() {
            @Override
            public ComponentDescriptor descriptor() {
                throw new AssertionError(SECRET);
            }

            @Override
            public ComponentContribution create(final ComponentCreationContext context) {
                throw new IllegalStateException("unreachable");
            }
        };

        RuntimeAssemblyException failure = assertThrows(
                RuntimeAssemblyException.class,
                () -> ComponentCatalog.builder().register("security-test", provider));

        assertEquals(RuntimeErrorCode.RUNTIME_CONTRIBUTION_INVALID, failure.errorCode());
        assertNoSecret(failure);
    }

    private static Lifecycle unsafeLifecycle() {
        return new Lifecycle() {
            @Override
            public LifecycleState state() {
                return LifecycleState.NEW;
            }

            @Override
            public void start() {
                throw new RuntimeAssemblyException(
                        RuntimeErrorCode.RUNTIME_COMPONENT_START_FAILED,
                        RuntimeFailurePhase.START,
                        PROVIDER_ID,
                        SECRET);
            }

            @Override
            public void stop() {
                // start never completed, so stop must not be called.
            }
        };
    }

    private static ConfigSource errorConfigSource() {
        return new ConfigSource() {
            @Override
            public ConfigSourceKind kind() {
                return ConfigSourceKind.FILE;
            }

            @Override
            public String id() {
                return "security-error-source";
            }

            @Override
            public Optional<String> value(final String alias) {
                throw new AssertionError(SECRET);
            }
        };
    }

    private static RuntimeAssembler.Builder assembler(final RuntimeComponentProvider provider) {
        ComponentCatalog catalog = ComponentCatalog.builder()
                .register("security-test", provider)
                .build();
        return RuntimeAssembler.builder(catalog, RuntimeProfile.local())
                .require(VALUE)
                .select(VALUE, PROVIDER_ID, "security-test");
    }

    private static void assertNoSecret(final Throwable failure) {
        assertFalse(String.valueOf(failure).contains(SECRET));
        assertFalse(String.valueOf(failure.getMessage()).contains(SECRET));
        assertNull(failure.getCause());
        for (Throwable suppressed : failure.getSuppressed()) {
            assertNoSecret(suppressed);
        }
    }
}
