package group.zn.zero.runtime.bootstrap;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.assembly.RuntimeComposition;
import group.zn.zero.runtime.assembly.RuntimeModule;
import group.zn.zero.runtime.assembly.RuntimeProfile;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.config.ConfigKey;
import group.zn.zero.runtime.config.ConfigSchema;
import group.zn.zero.runtime.config.ZeroConfigSource;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import group.zn.zero.runtime.spi.RuntimeProviders;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Minimal bootstrap: configuration and executors, with no networking or middleware dependency. */
public final class RuntimeBasics {
    public static final ComponentKey<ZeroConfig> CONFIG = ComponentKey.single(
            StandardRuntimeCapabilityModel.CONFIG, ZeroConfig.class);
    public static final ComponentKey<ZeroRuntimeExecutors> EXECUTORS = ComponentKey.single(
            StandardRuntimeCapabilityModel.EXECUTORS, ZeroRuntimeExecutors.class);
    private static final ConfigKey<String> MODE = ConfigKey.string(
                    StandardRuntimeCapabilityModel.LOCAL_CONFIG, ZeroRuntimeConfigKeys.ZERO_MODE)
            .defaultValue(ZeroRuntimeConfigKeys.MODE_LOCAL).validate(value -> !value.isBlank(), "non-blank").build();
    private static final ConfigKey<String> NAME = ConfigKey.string(
                    StandardRuntimeCapabilityModel.LOCAL_CONFIG, ZeroRuntimeConfigKeys.ZERO_NAME)
            .defaultValue(ZeroRuntimeConfigKeys.DEFAULT_NAME).validate(value -> !value.isBlank(), "non-blank").build();

    private RuntimeBasics() {
    }

    public static RuntimeComposition builder() {
        return builder(new MapZeroConfig(Map.of()));
    }

    public static RuntimeComposition builder(final ZeroConfig config) {
        return RuntimeComposition.builder(RuntimeProfile.local()).install(module(config, ZeroRuntimeExecutors.direct()));
    }

    public static RuntimeModule module(final ZeroConfig config, final ZeroRuntimeExecutors executors) {
        Objects.requireNonNull(executors, "executors");
        return module(config, () -> executors);
    }

    /** Creates owned executors only after planning succeeds and the provider is selected. */
    public static RuntimeModule module(final ZeroConfig config, final Supplier<ZeroRuntimeExecutors> executors) {
        ZeroConfig checkedConfig = Objects.requireNonNull(config, "config");
        RuntimeModule module = RuntimeModule.of("zero.bootstrap", providers(checkedConfig, executors), CONFIG, EXECUTORS);
        return new RuntimeModule(module.id(), module.providers(), module.preset(),
                List.of(new ZeroConfigSource("zero.bootstrap", checkedConfig)));
    }

    /** Ownership of the supplied executors transfers when the executor provider is created. */
    public static List<RuntimeComponentProvider> providers(final ZeroConfig config, final ZeroRuntimeExecutors executors) {
        Objects.requireNonNull(executors, "executors");
        return providers(config, () -> executors);
    }

    private static List<RuntimeComponentProvider> providers(
            final ZeroConfig config, final Supplier<ZeroRuntimeExecutors> executors) {
        ZeroConfig checkedConfig = Objects.requireNonNull(config, "config");
        Objects.requireNonNull(executors, "executors");
        return List.of(
                RuntimeProviders.create(ComponentDescriptor.builder(StandardRuntimeCapabilityModel.LOCAL_CONFIG)
                        .provide(CONFIG).kind(ComponentKind.LOCAL)
                        .configSchema(ConfigSchema.builder(StandardRuntimeCapabilityModel.LOCAL_CONFIG)
                                .add(MODE).add(NAME).build()).build(), context -> {
                            Map<String, String> values = new LinkedHashMap<>(checkedConfig.asMap());
                            values.put(ZeroRuntimeConfigKeys.ZERO_MODE, context.config().require(MODE));
                            values.put(ZeroRuntimeConfigKeys.ZERO_NAME, context.config().require(NAME));
                            return ComponentContribution.builder().bind(CONFIG, new MapZeroConfig(values)).build();
                        }),
                RuntimeProviders.create(ComponentDescriptor.builder(StandardRuntimeCapabilityModel.LOCAL_EXECUTORS)
                        .provide(EXECUTORS).kind(ComponentKind.LOCAL).build(), context -> {
                            ZeroRuntimeExecutors checkedExecutors = Objects.requireNonNull(executors.get(), "executors");
                            context.resources().register(checkedExecutors);
                            return ComponentContribution.builder().bind(EXECUTORS, checkedExecutors).build();
                        }));
    }
}
