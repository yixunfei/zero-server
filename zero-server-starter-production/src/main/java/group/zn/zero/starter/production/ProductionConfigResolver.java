package group.zn.zero.starter.production;

import group.zn.zero.core.config.ZeroConfig;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * production starter 配置解析器。
 *
 * <p>解析优先级为 {@link ZeroConfig}、JVM system property、环境变量。Adapter 专用严格入口不会修剪值，
 * 也不会把非法的高优先级值静默降级为低优先级来源；对外失败只公开稳定 Adapter 名称和逻辑配置键。
 *
 * @author zn
 */
final class ProductionConfigResolver {

    /** 未指定具体 Adapter 时使用的稳定归因名称。 */
    private static final String PRODUCTION_RUNTIME_ADAPTER = "production-runtime";

    /** 统一配置对象。 */
    private final ZeroConfig config;

    /** JVM system property 读取函数。 */
    private final Function<String, String> systemPropertyLookup;

    /** 进程环境变量读取函数。 */
    private final Function<String, String> environmentLookup;

    /**
     * 创建使用当前进程属性和环境变量的配置解析器。
     *
     * @param config 统一配置对象；不可为空。
     * @throws NullPointerException 配置对象为空时抛出。
     */
    ProductionConfigResolver(final ZeroConfig config) {
        this(config, System::getProperty, System::getenv);
    }

    /**
     * 创建允许隔离进程来源的配置解析器。
     *
     * <p>该构造器仅作为包内测试 seam，生产装配应使用单参数构造器。
     *
     * @param config 统一配置对象；不可为空。
     * @param systemPropertyLookup JVM system property 读取函数；不可为空，可返回空。
     * @param environmentLookup 环境变量读取函数；不可为空，可返回空。
     * @throws NullPointerException 任一参数为空时抛出。
     */
    ProductionConfigResolver(
            final ZeroConfig config,
            final Function<String, String> systemPropertyLookup,
            final Function<String, String> environmentLookup) {
        this.config = Objects.requireNonNull(config, "config");
        this.systemPropertyLookup = Objects.requireNonNull(systemPropertyLookup, "systemPropertyLookup");
        this.environmentLookup = Objects.requireNonNull(environmentLookup, "environmentLookup");
    }

    /**
     * 按逻辑键和来源候选读取 production 配置。
     *
     * <p>该兼容入口把失败归因到 production runtime。需要准确 Adapter 归因的装配代码应使用带
     * {@code adapterName} 的重载。
     *
     * @param logicalKey 逻辑键；不可为空。
     * @param sensitive 是否敏感。
     * @param configKeys ZeroConfig 键候选；不可为空，可为空集合，保持顺序。
     * @param systemProperties 系统属性候选；不可为空，可为空集合，保持顺序。
     * @param environmentVariables 环境变量候选；不可为空，可为空集合，保持顺序。
     * @return 已解析配置；不可为空；其值仅供装配内部使用，线程安全性取决于配置来源。
     * @throws ProductionAdapterException 首个已存在的候选值为空白时抛出；异常不包含配置值。
     */
    ResolvedProductionSetting read(
            final String logicalKey,
            final boolean sensitive,
            final List<String> configKeys,
            final List<String> systemProperties,
            final List<String> environmentVariables) {
        return read(
                PRODUCTION_RUNTIME_ADAPTER,
                logicalKey,
                sensitive,
                configKeys,
                systemProperties,
                environmentVariables);
    }

    /**
     * 按 Adapter、逻辑键和来源候选读取 production 配置。
     *
     * <p>每类来源按候选顺序解析。候选不存在时继续查找；候选存在但值为空白时立即失败，不允许退回低优先级
     * 来源。返回值不做修剪，避免把带前后空格的非法隔离标识悄悄改写为其他值。
     *
     * @param adapterName Adapter 稳定名称；不可为空白。
     * @param logicalKey 逻辑键；不可为空白。
     * @param sensitive 是否敏感。
     * @param configKeys ZeroConfig 键候选；不可为空，可为空集合，保持顺序。
     * @param systemProperties 系统属性候选；不可为空，可为空集合，保持顺序。
     * @param environmentVariables 环境变量候选；不可为空，可为空集合，保持顺序。
     * @return 已解析配置；不可为空；其值仅供装配内部使用，线程安全性取决于配置来源。
     * @throws ProductionAdapterException 首个已存在的候选值为空白时抛出；阶段为配置校验。
     * @throws NullPointerException 任一必填参数为空时抛出。
     * @throws IllegalArgumentException Adapter 名称、逻辑键或候选键为空白时抛出。
     */
    ResolvedProductionSetting read(
            final String adapterName,
            final String logicalKey,
            final boolean sensitive,
            final List<String> configKeys,
            final List<String> systemProperties,
            final List<String> environmentVariables) {
        return read(
                adapterName,
                logicalKey,
                sensitive,
                configKeys,
                systemProperties,
                environmentVariables,
                false);
    }

    /**
     * 读取允许空白值遮蔽低优先级来源并回到 schema 默认值的可选配置。
     *
     * <p>该入口只用于保留已经存在的 Adapter 可选配置语义：首个已存在来源为空白时返回 missing，
     * 不继续读取更低优先级来源。必填配置和严格可选配置仍必须使用 {@link #read}。</p>
     *
     * @param adapterName Adapter 稳定名称；不可为空白。
     * @param logicalKey 逻辑键；不可为空白。
     * @param sensitive 是否敏感。
     * @param configKeys ZeroConfig 键候选；不可为空，可为空集合，保持顺序。
     * @param systemProperties 系统属性候选；不可为空，可为空集合，保持顺序。
     * @param environmentVariables 环境变量候选；不可为空，可为空集合，保持顺序。
     * @return 已解析配置；不可为空；空白首选来源返回 missing。
     */
    ResolvedProductionSetting readDefaultable(
            final String adapterName,
            final String logicalKey,
            final boolean sensitive,
            final List<String> configKeys,
            final List<String> systemProperties,
            final List<String> environmentVariables) {
        return read(
                adapterName,
                logicalKey,
                sensitive,
                configKeys,
                systemProperties,
                environmentVariables,
                true);
    }

    private ResolvedProductionSetting read(
            final String adapterName,
            final String logicalKey,
            final boolean sensitive,
            final List<String> configKeys,
            final List<String> systemProperties,
            final List<String> environmentVariables,
            final boolean blankMeansMissing) {
        String currentAdapterName = requireText(adapterName, "adapterName");
        String currentLogicalKey = requireText(logicalKey, "logicalKey");
        ResolvedProductionSetting configured = firstCandidate(
                currentAdapterName,
                currentLogicalKey,
                sensitive,
                checkedKeys(configKeys, "configKeys"),
                key -> config.get(key).orElse(null),
                "ZeroConfig",
                blankMeansMissing);
        if (configured != null) {
            return configured;
        }
        ResolvedProductionSetting system = firstCandidate(
                currentAdapterName,
                currentLogicalKey,
                sensitive,
                checkedKeys(systemProperties, "systemProperties"),
                systemPropertyLookup,
                "systemProperty",
                blankMeansMissing);
        if (system != null) {
            return system;
        }
        ResolvedProductionSetting environment = firstCandidate(
                currentAdapterName,
                currentLogicalKey,
                sensitive,
                checkedKeys(environmentVariables, "environmentVariables"),
                environmentLookup,
                "environment",
                blankMeansMissing);
        if (environment != null) {
            return environment;
        }
        return new ResolvedProductionSetting(currentLogicalKey, Optional.empty(), Optional.empty());
    }

    /**
     * 按单个配置键读取兼容布尔值。
     *
     * <p>该方法保留 network 既有 {@link Boolean#parseBoolean(String)} 行为。Production Adapter 选择器必须使用
     * {@link #strictEnabled(String, String)}。
     *
     * @param key 配置键；不可为空。
     * @return true 表示兼容解析结果为启用；线程安全性取决于配置来源。
     * @throws NullPointerException 配置键为空时抛出。
     */
    boolean enabled(final String key) {
        return config.get(Objects.requireNonNull(key, "key"))
                .map(Boolean::parseBoolean)
                .orElse(false);
    }

    /**
     * 严格读取 Production Adapter enabled 选择器。
     *
     * <p>键缺失表示禁用；键存在时仅接受精确小写 {@code true} 或 {@code false}。方法不修剪原始值。
     *
     * @param adapterName Adapter 稳定名称；不可为空白。
     * @param key 选择器逻辑键；不可为空白。
     * @return true 表示显式启用，false 表示缺失或显式禁用；线程安全性取决于配置来源。
     * @throws ProductionAdapterException 值不是精确小写布尔文本时抛出；异常不包含配置值。
     * @throws NullPointerException 任一参数为空时抛出。
     * @throws IllegalArgumentException 任一文本参数为空白时抛出。
     */
    boolean strictEnabled(final String adapterName, final String key) {
        String currentAdapterName = requireText(adapterName, "adapterName");
        String currentKey = requireText(key, "key");
        Optional<String> configured = config.get(currentKey);
        if (configured.isEmpty()) {
            return false;
        }
        return switch (configured.get()) {
            case "true" -> true;
            case "false" -> false;
            default -> throw invalidSelection(currentAdapterName, currentKey);
        };
    }

    /**
     * 严格读取枚举式 Production Adapter 选择器。
     *
     * <p>键缺失时返回默认值；键存在时仅接受 {@code allowedValues} 中的精确文本。方法不忽略大小写且不修剪
     * 原始值。
     *
     * @param adapterName Adapter 稳定名称；不可为空白。
     * @param key 选择器逻辑键；不可为空白。
     * @param defaultValue 键缺失时使用的默认值；不可为空白且必须属于允许集合。
     * @param allowedValues 允许值；不可为空或为空集合，元素不可为空白。
     * @return 配置值或默认值；不可为空；线程安全性取决于配置来源。
     * @throws ProductionAdapterException 已存在的配置值不属于允许集合时抛出；异常不包含配置值。
     * @throws NullPointerException 任一必填参数为空时抛出。
     * @throws IllegalArgumentException 默认值不属于允许集合，或任一文本参数为空白时抛出。
     */
    String strictChoice(
            final String adapterName,
            final String key,
            final String defaultValue,
            final List<String> allowedValues) {
        String currentAdapterName = requireText(adapterName, "adapterName");
        String currentKey = requireText(key, "key");
        String currentDefault = requireText(defaultValue, "defaultValue");
        List<String> currentAllowedValues = checkedKeys(allowedValues, "allowedValues");
        if (currentAllowedValues.isEmpty()) {
            throw new IllegalArgumentException("allowedValues must not be empty");
        }
        if (!currentAllowedValues.contains(currentDefault)) {
            throw new IllegalArgumentException("defaultValue must be allowed");
        }
        Optional<String> configured = config.get(currentKey);
        if (configured.isEmpty()) {
            return currentDefault;
        }
        String value = configured.get();
        if (!currentAllowedValues.contains(value)) {
            throw invalidSelection(currentAdapterName, currentKey);
        }
        return value;
    }

    /**
     * 返回 Adapter 必填配置值。
     *
     * @param adapterName Adapter 稳定名称；不可为空白。
     * @param setting 已解析配置；不可为空。
     * @return 配置值；不可为空；是否敏感及线程安全性取决于来源。
     * @throws ProductionAdapterException 配置缺失时抛出；异常只包含逻辑配置键。
     * @throws NullPointerException 任一参数为空时抛出。
     * @throws IllegalArgumentException Adapter 名称为空白时抛出。
     */
    String required(final String adapterName, final ResolvedProductionSetting setting) {
        String currentAdapterName = requireText(adapterName, "adapterName");
        ResolvedProductionSetting currentSetting = Objects.requireNonNull(setting, "setting");
        return currentSetting.value().orElseThrow(() -> ProductionAdapterFailures.failure(
                currentAdapterName,
                ProductionAdapterFailurePhase.CONFIG_VALIDATION,
                ProductionAdapterErrorCode.CONFIG_MISSING,
                "missing production adapter config key: " + currentSetting.logicalKey()));
    }

    /**
     * 严格读取 Production Adapter 正整数配置。
     *
     * <p>键缺失时使用默认值。空白、非十进制整数、溢出、零或负数都映射为不携带原始值的安全配置异常。
     *
     * @param adapterName Adapter 稳定名称；不可为空白。
     * @param key 配置逻辑键；不可为空白。
     * @param defaultValue 键缺失时使用的默认值；必须大于 0。
     * @return 正整数配置值；线程安全性取决于配置来源。
     * @throws ProductionAdapterException 已存在的值不是正整数，或默认值不合法时抛出；异常不包含配置值。
     * @throws NullPointerException 任一文本参数为空时抛出。
     * @throws IllegalArgumentException Adapter 名称或配置键为空白时抛出。
     */
    int strictPositiveInt(final String adapterName, final String key, final int defaultValue) {
        String currentAdapterName = requireText(adapterName, "adapterName");
        String currentKey = requireText(key, "key");
        Optional<String> configured = config.get(currentKey);
        if (configured.isEmpty()) {
            if (defaultValue <= 0) {
                throw invalidValue(currentAdapterName, currentKey);
            }
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(configured.get());
            if (parsed <= 0) {
                throw invalidValue(currentAdapterName, currentKey);
            }
            return parsed;
        } catch (NumberFormatException ignored) {
            throw invalidValue(currentAdapterName, currentKey);
        }
    }

    private ResolvedProductionSetting firstCandidate(
            final String adapterName,
            final String logicalKey,
            final boolean sensitive,
            final List<String> keys,
            final Function<String, String> lookup,
            final String sourceType,
            final boolean blankMeansMissing) {
        for (String key : keys) {
            String value = lookup.apply(key);
            if (value == null) {
                continue;
            }
            if (value.isBlank()) {
                if (blankMeansMissing) {
                    return new ResolvedProductionSetting(logicalKey, Optional.empty(), Optional.empty());
                }
                throw invalidValue(adapterName, logicalKey);
            }
            return resolved(logicalKey, value, sourceType, key, sensitive);
        }
        return null;
    }

    private ResolvedProductionSetting resolved(
            final String logicalKey,
            final String value,
            final String sourceType,
            final String sourceKey,
            final boolean sensitive) {
        return new ResolvedProductionSetting(
                logicalKey,
                Optional.of(value),
                Optional.of(new ZeroProductionConfigSource(logicalKey, sourceType, sourceKey, sensitive)));
    }

    private static ProductionAdapterException invalidSelection(
            final String adapterName,
            final String logicalKey) {
        return ProductionAdapterFailures.invalidConfig(
                adapterName,
                ProductionAdapterFailurePhase.CONFIG_SELECTION,
                logicalKey);
    }

    private static ProductionAdapterException invalidValue(
            final String adapterName,
            final String logicalKey) {
        return ProductionAdapterFailures.invalidConfig(
                adapterName,
                ProductionAdapterFailurePhase.CONFIG_VALIDATION,
                logicalKey);
    }

    private static List<String> checkedKeys(final List<String> keys, final String name) {
        List<String> currentKeys = List.copyOf(Objects.requireNonNull(keys, name));
        for (String key : currentKeys) {
            requireText(key, name + " element");
        }
        return currentKeys;
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }

    private static boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }
}
