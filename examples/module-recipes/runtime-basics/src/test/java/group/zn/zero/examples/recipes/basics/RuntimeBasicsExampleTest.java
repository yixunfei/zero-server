package group.zn.zero.examples.recipes.basics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.runtime.assembly.RuntimeComposition;
import group.zn.zero.runtime.assembly.RuntimeProfile;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 最小接入回归：任务确实进入受管域，诊断不提前创建执行器。 @author zn */
class RuntimeBasicsExampleTest {
    /** 验证配置、任务完成和关闭。 @throws Exception 异步处理失败。 */
    @Test
    void runsManagedTaskAndClosesRuntime() throws Exception {
        var result = RuntimeBasicsExample.runFlow();
        assertEquals(2, result.componentCount());
        assertEquals("local-cn", result.region());
        assertEquals("zh-CN", result.language());
        assertTrue(result.worker().startsWith("recipe-basics-logic-"));
        assertTrue(result.started());
        assertTrue(result.stopped());
    }

    /** 验证 planning 的无资源副作用及必填配置失败入口。 */
    @Test
    void diagnosisDoesNotCreateExecutorsAndMissingConfigHasCode() {
        var creations = new AtomicInteger();
        var composition = RuntimeComposition.builder(RuntimeProfile.local())
                .install(RuntimeBasics.module(new MapZeroConfig(Map.of()), () -> {
                    creations.incrementAndGet();
                    return ZeroRuntimeExecutors.direct();
                }));
        composition.diagnose();
        assertEquals(0, creations.get());
        try (var runtime = composition.build()) {
            assertEquals(1, creations.get());
            var failure = assertThrows(ZeroException.class, () -> runtime.require(RuntimeBasics.CONFIG).require("absent"));
            assertEquals(SystemErrorCode.CONFIGURATION_ERROR, failure.errorCode());
        }
    }
}
