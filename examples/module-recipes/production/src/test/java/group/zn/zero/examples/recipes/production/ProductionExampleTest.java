package group.zn.zero.examples.recipes.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.runtime.production.ProductionAdapterErrorCode;
import group.zn.zero.runtime.production.ZeroProductionAdapterState;
import group.zn.zero.runtime.production.ZeroProductionRuntimeConfigKeys;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 生产接入诊断与无外部 Adapter 生命周期回归。 @author zn */
class ProductionExampleTest {
    /** 缺配置时失败且不暴露值；基础运行时可启动关闭。 */
    @Test
    void diagnosesMissingCacheConfigurationAndClosesBaseRuntime() {
        var result = ProductionExample.runFlow();
        assertEquals(ZeroProductionAdapterState.MISSING_CONFIG, result.redisState());
        assertEquals(List.of("builder.redisCacheValueCodec",
                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_CACHE_NAME,
                ZeroProductionRuntimeConfigKeys.ADAPTER_CACHE_REDIS_NAMESPACE), result.missingKeys());
        assertEquals(ProductionAdapterErrorCode.CONFIG_MISSING.code(), result.failureCode());
        assertTrue(result.redacted());
        assertTrue(result.started());
        assertTrue(result.stopped());
        assertTrue(result.managedIo());
    }
}
