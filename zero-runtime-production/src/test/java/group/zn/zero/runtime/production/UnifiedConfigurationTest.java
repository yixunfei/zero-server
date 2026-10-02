package group.zn.zero.runtime.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.config.SourcedZeroConfig;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.config.ConfigKey;
import group.zn.zero.runtime.config.ConfigSchema;
import group.zn.zero.runtime.config.ConfigSourceKind;
import group.zn.zero.runtime.config.RuntimeConfigResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证 production decoder、typed 消费及诊断共用候选值与来源。@author zn */
class UnifiedConfigurationTest {
    /** 统一诊断不创建资源，缺配置时不制造完整计划；完整构建的快照保持相等。 */
    @Test
    void diagnosisRepresentsMissingAndCompletePlansWithoutResourceCreation() {
        var missing = ProductionAssembly.builder("external-test", new MapZeroConfig(Map.of()))
                .configSourceLookups(key -> null, key -> null)
                .install(context -> ProductionModule.single(null, List.of(), new ProductionAdapterDiagnostic(
                        "test", ZeroProductionAdapterState.MISSING_CONFIG, List.of("test.secret"),
                        List.of(), List.of("test.secret"), List.of(), List.of())))
                .diagnosis();
        assertEquals(List.of("test.secret"), missing.missingConfigKeys());
        assertEquals(java.util.Optional.empty(), missing.plan());
        var assembly = ProductionAssembly.builder("external-test", new MapZeroConfig(Map.of("zero.name", "secret-name")))
                .configSourceLookups(key -> null, key -> null);
        var diagnosis = assembly.diagnosis();
        assertEquals(List.of(), diagnosis.missingConfigKeys());
        assertFalse(diagnosis.toString().contains("secret-name"));
        try (var runtime = assembly.build()) {
            assertEquals(diagnosis.plan().orElseThrow(), runtime.plan());
        }
    }

    /** 默认开关不遮蔽系统值，文件和默认来源贯通 typed 诊断。 */
    @Test
    void sourceAndValueAgreeAcrossProductionAndTypedResolution() {
        ComponentId owner = ComponentId.of("test.adapter");
        var config = SourcedZeroConfig.withDefaults(new MapZeroConfig(Map.of()), Map.of("zero.test", "default-secret"));
        var resolver = new ProductionConfigResolver(config, key -> "property-secret", key -> "environment-secret");
        var setting = resolver.read("test", "zero.test", true, List.of("zero.test"), List.of("zero.test"), List.of("ZERO_TEST"));
        var key = ConfigKey.string(owner, "zero.test").sensitive().build();
        var resolved = RuntimeConfigResolver.resolve(List.of(ConfigSchema.builder(owner).add(key).build()),
                ProductionConfigSources.from(owner, List.of(setting)));
        assertEquals(setting.require(), resolved.component(owner).require(key));
        assertEquals("property-secret", resolved.component(owner).require(key));
        assertEquals(ConfigSourceKind.SYSTEM_PROPERTY, resolved.diagnostics().entries().getFirst().sourceKind());
        assertEquals("***", resolved.diagnostics().entries().getFirst().effectiveValue().orElseThrow());
        assertFalse(resolved.toString().contains("property-secret"));
        var defaults = new ProductionConfigResolver(config, alias -> null, alias -> null);
        var defaultSetting = defaults.read("test", "zero.test", true, List.of("zero.test"), List.of(), List.of());
        var defaultResolved = RuntimeConfigResolver.resolve(List.of(ConfigSchema.builder(owner).add(key).build()),
                ProductionConfigSources.from(owner, List.of(defaultSetting)));
        assertEquals(ConfigSourceKind.DEFAULT, defaultResolved.diagnostics().entries().getFirst().sourceKind());
    }

    /** 开关和数值预算与 Adapter 配置使用相同优先级和严格校验。 */
    @Test
    void selectorsAndBudgetsUseSharedLookupWithoutInvalidFallback() {
        var config = SourcedZeroConfig.withDefaults(new MapZeroConfig(Map.of()), Map.of("enabled", "false", "budget", "1"));
        var resolver = new ProductionConfigResolver(config, key -> key.equals("enabled") ? "true" : "7", key -> null);
        assertEquals(true, resolver.strictEnabled("test", "enabled"));
        assertEquals(7, resolver.strictPositiveInt("test", "budget", 1));
        var invalid = new ProductionConfigResolver(config, key -> "invalid-secret", key -> "true");
        var failure = assertThrows(ProductionAdapterException.class, () -> invalid.strictEnabled("test", "enabled"));
        assertFalse(failure.toString().contains("invalid-secret"));
    }
}
