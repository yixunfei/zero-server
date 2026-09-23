package group.zn.zero.runtime.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.runtime.production.ProductionAdapterErrorCode;
import group.zn.zero.runtime.production.ProductionAdapterException;
import group.zn.zero.runtime.production.ProductionAdapterFailurePhase;
import group.zn.zero.runtime.production.ProductionConfigResolver;
import group.zn.zero.runtime.production.ResolvedProductionSetting;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Production Adapter 严格配置解析测试。
 *
 * @author zn
 */
class ProductionConfigResolverTest {

    /** Adapter 测试归因名称。 */
    private static final String ADAPTER_NAME = "test-adapter";

    /** Adapter 选择器测试键。 */
    private static final String ENABLED_KEY = "zero.adapter.test.enabled";

    /** Nacos mode 测试键。 */
    private static final String MODE_KEY = "zero.discovery.mode";

    /** 普通配置逻辑键。 */
    private static final String LOGICAL_KEY = "zero.adapter.test.required";

    /**
     * 验证 enabled 缺失时禁用，且精确小写布尔值可以通过。
     */
    @Test
    void strictEnabledShouldAcceptOnlyExactLowercaseBooleans() {
        assertFalse(resolver(Map.of()).strictEnabled(ADAPTER_NAME, ENABLED_KEY));
        assertTrue(resolver(Map.of(ENABLED_KEY, "true")).strictEnabled(ADAPTER_NAME, ENABLED_KEY));
        assertFalse(resolver(Map.of(ENABLED_KEY, "false")).strictEnabled(ADAPTER_NAME, ENABLED_KEY));
    }

    /**
     * 验证 enabled 的空白、大小写变体和宽松布尔文本全部安全失败。
     */
    @Test
    void strictEnabledShouldRejectMalformedValuesWithoutLeakingThem() {
        for (String invalid : List.of("", " ", "TRUE", "False", "tru", "yes", "1", " true ")) {
            ProductionAdapterException failure = assertThrows(
                    ProductionAdapterException.class,
                    () -> resolver(Map.of(ENABLED_KEY, invalid)).strictEnabled(ADAPTER_NAME, ENABLED_KEY));

            assertConfigFailure(
                    failure,
                    ProductionAdapterFailurePhase.CONFIG_SELECTION,
                    ProductionAdapterErrorCode.CONFIG_INVALID,
                    ENABLED_KEY);
        }
    }

    /**
     * 验证 discovery mode 缺失时选择 local，且只接受精确允许值。
     */
    @Test
    void strictChoiceShouldDefaultAndAcceptOnlyExactAllowedValues() {
        List<String> allowed = List.of("local", "nacos");

        assertEquals("local", resolver(Map.of()).strictChoice(ADAPTER_NAME, MODE_KEY, "local", allowed));
        assertEquals("local", resolver(Map.of(MODE_KEY, "local"))
                .strictChoice(ADAPTER_NAME, MODE_KEY, "local", allowed));
        assertEquals("nacos", resolver(Map.of(MODE_KEY, "nacos"))
                .strictChoice(ADAPTER_NAME, MODE_KEY, "local", allowed));

        for (String invalid : List.of("", " ", "NACOS", "LOCAL", " nacos ", "unknown")) {
            ProductionAdapterException failure = assertThrows(
                    ProductionAdapterException.class,
                    () -> resolver(Map.of(MODE_KEY, invalid))
                            .strictChoice(ADAPTER_NAME, MODE_KEY, "local", allowed));
            assertConfigFailure(
                    failure,
                    ProductionAdapterFailurePhase.CONFIG_SELECTION,
                    ProductionAdapterErrorCode.CONFIG_INVALID,
                    MODE_KEY);
        }
    }

    /**
     * 验证 ZeroConfig 中已存在的空白值不会退回低优先级来源。
     */
    @Test
    void readShouldRejectBlankZeroConfigBeforeFallback() {
        String sourceKey = "system.property.secret.source";
        ProductionConfigResolver resolver = new ProductionConfigResolver(
                new MapZeroConfig(Map.of("config.source", " ")),
                key -> sourceKey.equals(key) ? "lower-priority-secret" : null,
                key -> null);

        ProductionAdapterException failure = assertThrows(
                ProductionAdapterException.class,
                () -> resolver.read(
                        ADAPTER_NAME,
                        LOGICAL_KEY,
                        true,
                        List.of("config.source"),
                        List.of(sourceKey),
                        List.of()));

        assertConfigFailure(
                failure,
                ProductionAdapterFailurePhase.CONFIG_VALIDATION,
                ProductionAdapterErrorCode.CONFIG_INVALID,
                LOGICAL_KEY);
        assertFalse(failure.message().contains(sourceKey));
        assertFalse(failure.message().contains("lower-priority-secret"));
    }

    /**
     * 验证 system property 已存在但空白时按非法值失败，而不是视为缺失。
     */
    @Test
    void readShouldDistinguishBlankSystemPropertyFromAbsentProperty() {
        String sourceKey = "system.property.source";
        ProductionConfigResolver resolver = new ProductionConfigResolver(
                new MapZeroConfig(Map.of()),
                key -> sourceKey.equals(key) ? "\t" : null,
                key -> null);

        ProductionAdapterException failure = assertThrows(
                ProductionAdapterException.class,
                () -> resolver.read(
                        ADAPTER_NAME,
                        LOGICAL_KEY,
                        true,
                        List.of(),
                        List.of(sourceKey),
                        List.of()));

        assertConfigFailure(
                failure,
                ProductionAdapterFailurePhase.CONFIG_VALIDATION,
                ProductionAdapterErrorCode.CONFIG_INVALID,
                LOGICAL_KEY);
        assertFalse(failure.message().contains(sourceKey));
    }

    /**
     * 验证 environment 已存在但空白时按非法值失败，而不是视为缺失。
     */
    @Test
    void readShouldDistinguishBlankEnvironmentFromAbsentVariable() {
        String sourceKey = "ZERO_TEST_SECRET_SOURCE";
        ProductionConfigResolver resolver = new ProductionConfigResolver(
                new MapZeroConfig(Map.of()),
                key -> null,
                key -> sourceKey.equals(key) ? " " : null);

        ProductionAdapterException failure = assertThrows(
                ProductionAdapterException.class,
                () -> resolver.read(
                        ADAPTER_NAME,
                        LOGICAL_KEY,
                        true,
                        List.of(),
                        List.of(),
                        List.of(sourceKey)));

        assertConfigFailure(
                failure,
                ProductionAdapterFailurePhase.CONFIG_VALIDATION,
                ProductionAdapterErrorCode.CONFIG_INVALID,
                LOGICAL_KEY);
        assertFalse(failure.message().contains(sourceKey));
    }

    /**
     * 验证所有来源都缺失时返回 missing，并保留命中来源的键名而不进入文本值摘要。
     */
    @Test
    void readShouldDistinguishAbsentAndResolvedSources() {
        ProductionConfigResolver missingResolver = new ProductionConfigResolver(
                new MapZeroConfig(Map.of()),
                key -> null,
                key -> null);
        ResolvedProductionSetting missing = missingResolver.read(
                ADAPTER_NAME,
                LOGICAL_KEY,
                true,
                List.of("config.source"),
                List.of("system.source"),
                List.of("ENV_SOURCE"));

        assertFalse(missing.present());
        assertEquals(Optional.empty(), missing.source());

        ProductionConfigResolver resolvedResolver = new ProductionConfigResolver(
                new MapZeroConfig(Map.of()),
                key -> "system.source".equals(key) ? "secret-value" : null,
                key -> null);
        ResolvedProductionSetting resolved = resolvedResolver.read(
                ADAPTER_NAME,
                LOGICAL_KEY,
                true,
                List.of(),
                List.of("system.source"),
                List.of());

        assertEquals("secret-value", resolved.require());
        assertEquals("system.source", resolved.source().orElseThrow().sourceKey());
        assertFalse(resolved.toString().contains("secret-value"));
    }

    /**
     * 验证 defaultable 可选项允许空白首选来源回到默认值，但不会读取低优先级敏感值。
     */
    @Test
    void readDefaultableShouldTreatBlankAsMissingWithoutFallback() {
        String systemKey = "system.optional.secret";
        ProductionConfigResolver resolver = new ProductionConfigResolver(
                new MapZeroConfig(Map.of("config.optional", " ")),
                key -> systemKey.equals(key) ? "lower-priority-secret" : null,
                key -> null);

        ResolvedProductionSetting setting = resolver.readDefaultable(
                ADAPTER_NAME,
                LOGICAL_KEY,
                true,
                List.of("config.optional"),
                List.of(systemKey),
                List.of());

        assertFalse(setting.present());
        assertEquals(Optional.empty(), setting.source());
        assertFalse(setting.toString().contains("lower-priority-secret"));
    }

    /**
     * 验证 required 缺失时绑定真实缺配置错误码，且异常图不保存原始值或 cause。
     */
    @Test
    void requiredShouldFailWithSafeMissingConfigError() {
        ResolvedProductionSetting missing = new ResolvedProductionSetting(
                LOGICAL_KEY,
                Optional.empty(),
                Optional.empty());

        ProductionAdapterException failure = assertThrows(
                ProductionAdapterException.class,
                () -> resolver(Map.of()).required(ADAPTER_NAME, missing));

        assertConfigFailure(
                failure,
                ProductionAdapterFailurePhase.CONFIG_VALIDATION,
                ProductionAdapterErrorCode.CONFIG_MISSING,
                LOGICAL_KEY);
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }

    /**
     * 验证 Adapter 正整数路径不会公开非法原值，并可解析缺省值与显式值。
     */
    @Test
    void strictPositiveIntShouldUseSafeConfigFailure() {
        assertEquals(10, resolver(Map.of()).strictPositiveInt(ADAPTER_NAME, LOGICAL_KEY, 10));
        assertEquals(42, resolver(Map.of(LOGICAL_KEY, "42"))
                .strictPositiveInt(ADAPTER_NAME, LOGICAL_KEY, 10));

        for (String invalid : List.of("", " ", "0", "-1", "1.5", "2147483648", "secret-number")) {
            ProductionAdapterException failure = assertThrows(
                    ProductionAdapterException.class,
                    () -> resolver(Map.of(LOGICAL_KEY, invalid))
                            .strictPositiveInt(ADAPTER_NAME, LOGICAL_KEY, 10));
            assertConfigFailure(
                    failure,
                    ProductionAdapterFailurePhase.CONFIG_VALIDATION,
                    ProductionAdapterErrorCode.CONFIG_INVALID,
                    LOGICAL_KEY);
        }
    }

    /**
     * 验证 network enabled 入口保持原有宽松布尔行为。
     */
    @Test
    void networkEnabledShouldKeepExistingParsingBehavior() {
        ProductionConfigResolver resolver = resolver(Map.of(ENABLED_KEY, "TRUE"));

        assertTrue(resolver.enabled(ENABLED_KEY));
        assertFalse(resolver(Map.of(ENABLED_KEY, " true ")).enabled(ENABLED_KEY));
    }

    private static ProductionConfigResolver resolver(final Map<String, String> values) {
        return new ProductionConfigResolver(new MapZeroConfig(values));
    }

    private static void assertConfigFailure(
            final ProductionAdapterException failure,
            final ProductionAdapterFailurePhase phase,
            final ProductionAdapterErrorCode errorCode,
            final String logicalKey) {
        assertEquals(ADAPTER_NAME, failure.adapterName());
        assertEquals(phase, failure.failurePhase());
        assertSame(errorCode, failure.errorCode());
        assertEquals(
                (errorCode == ProductionAdapterErrorCode.CONFIG_MISSING
                        ? "missing production adapter config key: "
                        : "invalid production adapter config key: ") + logicalKey,
                failure.message());
        assertNull(failure.getCause());
    }
}
