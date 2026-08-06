package group.zn.zero.starter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.actor.LaneKey;
import group.zn.zero.cache.InMemoryCacheService;
import group.zn.zero.codegen.ProtocolCodegenOptions;
import group.zn.zero.codegen.ProtocolCodegenRunner;
import group.zn.zero.codegen.dsl.ProtocolDslDocument;
import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.lifecycle.AbstractLifecycle;
import group.zn.zero.discovery.nacos.InMemoryServiceDiscovery;
import group.zn.zero.discovery.nacos.ServiceInstance;
import group.zn.zero.log.InMemoryLogSink;
import group.zn.zero.log.LogAppender;
import group.zn.zero.log.LogType;
import group.zn.zero.log.ZeroLogRecord;
import group.zn.zero.logic.LocalLogicExample;
import group.zn.zero.logic.LogicFlowResult;
import group.zn.zero.protocol.ProtocolDirection;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import group.zn.zero.rpc.spi.RpcTransport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * zeroServer 应用启动测试。
 *
 * @author zn
 */
class ZeroServerApplicationTest {

    /**
     * 协议生成临时输出目录。
     */
    @TempDir
    private Path tempDir;

    /**
     * 验证最小应用生命周期可以启动和停止。
     */
    @Test
    void applicationShouldStartAndStop() {
        ZeroServerApplication application = new ZeroServerApplication(
                new MapZeroConfig(Map.of("zero.mode", "test")),
                new InMemoryLogSink());
        application.start();
        assertTrue(application.running());
        application.stop();
        assertFalse(application.running());
    }

    /**
     * 验证应用会持有并管理统一运行时组件。
     */
    @Test
    void applicationShouldManageRuntimeComponents() {
        InMemoryLogSink sink = new InMemoryLogSink();
        ZeroRuntimeComponents components = ZeroRuntimeFactory.localDefault(
                new MapZeroConfig(Map.of("zero.mode", "test")),
                sink);
        ZeroServerApplication application = new ZeroServerApplication(components);

        application.start();

        assertSame(components, application.components());
        assertTrue(components.running());
        assertTrue(components.persistenceManager().running());

        application.stop();

        assertFalse(application.running());
        assertFalse(components.running());
        assertFalse(components.persistenceManager().running());
    }

    /**
     * 验证 starter 启动日志通过统一安全日志端口写入。
     */
    @Test
    void applicationShouldWriteStartupLogThroughSink() {
        InMemoryLogSink sink = new InMemoryLogSink();
        ZeroServerApplication application = new ZeroServerApplication(
                new MapZeroConfig(Map.of("zero.mode", "test")),
                sink);

        application.start();

        List<ZeroLogRecord> records = sink.records();
        assertEquals(1, records.size());
        ZeroLogRecord record = records.getFirst();
        assertEquals(LogType.RUNTIME, record.logType());
        assertEquals("zero-server-starter", record.module());
        assertEquals("zeroServer started", record.message());
        assertEquals("test", record.fields().get("mode"));
        assertEquals("test", record.fields().get("runtimeMode"));
        application.stop();
    }

    /**
     * 验证 starter builder 可以覆盖单个组件，并能返回装配诊断报告。
     */
    @Test
    void runtimeBuilderShouldOverrideSingleComponentAndReportAssembly() {
        InMemoryCacheService<Object, Object> cacheService = new InMemoryCacheService<>();
        ZeroRuntimeComponents components = ZeroRuntimeFactory.localBuilder(new MapZeroConfig(Map.of(
                        "zero.mode", "custom",
                        "zero.name", "custom-runtime")))
                .cacheService(cacheService)
                .build();

        ZeroRuntimeAssemblyReport report = components.assemblyReport();

        assertSame(cacheService, components.cacheService());
        assertEquals("custom", report.mode());
        assertEquals("custom-runtime", report.name());
        assertEquals(cacheService.getClass().getName(), report.componentType("cacheService").orElseThrow());
        assertTrue(report.componentType("logAppender").orElseThrow().contains("LogPipeline"));
        assertTrue(report.lifecycleComponentTypes().stream()
                .anyMatch(type -> type.endsWith("DefaultPersistenceManager")));
    }

    /**
     * 验证构建前装配的 observer 与最终运行时共享同一安全日志管线，且冻结后不能替换终端 SPI。
     */
    @Test
    void runtimeBuilderShouldFreezeAndReuseSafeLogAppender() {
        InMemoryLogSink terminalLogSink = new InMemoryLogSink();
        ZeroRuntimeBuilder builder = ZeroRuntimeFactory.localBuilder(
                new MapZeroConfig(Map.of("zero.mode", "test")),
                terminalLogSink);

        LogAppender logAppender = builder.logAppender();
        ZeroRuntimeComponents components = builder.build();

        assertSame(logAppender, components.logAppender());
        assertThrows(IllegalStateException.class, () -> builder.terminalLogSink(new InMemoryLogSink()));
        assertThrows(
                IllegalStateException.class,
                () -> builder.config(new MapZeroConfig(Map.of("zero.mode", "changed"))));
    }

    /**
     * 验证 builder 可以显式覆盖生命周期组件顺序。
     */
    @Test
    void runtimeBuilderShouldUseExplicitLifecycleOrder() {
        List<String> steps = new ArrayList<>();
        CountingLifecycle first = new CountingLifecycle("first", steps);
        CountingLifecycle second = new CountingLifecycle("second", steps);
        ZeroRuntimeComponents components = ZeroRuntimeFactory.localBuilder(new MapZeroConfig(Map.of("zero.mode", "test")))
                .lifecycleComponents(List.of(first, second))
                .build();

        components.start();
        components.stop();

        assertEquals(List.of("start:first", "start:second", "stop:second", "stop:first"), steps);
    }

    /**
     * 验证 builder 对同一个附加生命周期组件按身份去重。
     */
    @Test
    void runtimeBuilderShouldDeduplicateAdditionalLifecycleComponentByIdentity() {
        List<String> steps = new ArrayList<>();
        CountingLifecycle lifecycle = new CountingLifecycle("extra", steps);
        ZeroRuntimeComponents components = ZeroRuntimeFactory.localBuilder(new MapZeroConfig(Map.of("zero.mode", "test")))
                .addLifecycleComponent(lifecycle)
                .addLifecycleComponent(lifecycle)
                .build();

        components.start();
        components.stop();

        assertEquals(2, components.lifecycleComponents().size());
        assertEquals(List.of("start:extra", "stop:extra"), steps);
    }

    /**
     * 验证 builder 拒绝只有 RPC transport 而没有 handler registry 的不一致组合。
     */
    @Test
    void runtimeBuilderShouldRejectHalfRpcOverride() {
        ZeroException exception = assertThrows(ZeroException.class, () -> ZeroRuntimeFactory
                .localBuilder(new MapZeroConfig(Map.of("zero.mode", "test")))
                .rpcTransport(new TransportOnlyRpc())
                .build());

        assertEquals(SystemErrorCode.INVALID_ARGUMENT.code(), exception.code());
    }

    /**
     * 验证 builder 可以识别同时实现 RPC transport 与 handler registry 的组件。
     */
    @Test
    void runtimeBuilderShouldAcceptRpcComponentImplementingBothContracts() {
        group.zn.zero.rpc.local.InMemoryRpcTransport rpc = new group.zn.zero.rpc.local.InMemoryRpcTransport();
        ZeroRuntimeComponents components = ZeroRuntimeFactory.localBuilder(new MapZeroConfig(Map.of("zero.mode", "test")))
                .rpcTransport(rpc)
                .build();

        assertSame(rpc, components.rpcTransport());
        assertSame(rpc, components.rpcHandlerRegistry());
        assertInstanceOf(group.zn.zero.rpc.local.InMemoryRpcTransport.class, components.rpcTransport());
    }

    /**
     * 验证 starter 完整本地装配 demo 可以无 Docker 启动并跑通核心组件。
     *
     * @throws Exception 本地网络 demo 启动或收发失败时抛出。
     */
    @Test
    void fullLocalDemoStartShouldExerciseRuntimeComponents() throws Exception {
        ZeroServerFullLocalDemoStart.DemoResult result = ZeroServerFullLocalDemoStart.runDemo();

        assertEquals("demo=ok|event=1|actor=1|rpc=ping|cache=demo-value", result.responseText());
        assertEquals(1, result.eventCount());
        assertEquals(1, result.actorCount());
        assertEquals(2, result.logCount());
        assertTrue(result.persistenceRunning());
        assertTrue(result.applicationRunning());
        assertTrue(result.handlerThreadName().contains("zero-starter-demo-logic"));
    }

    /**
     * 验证 starter 测试边界可以可选接入服务发现策略，不改变默认本地启动行为。
     */
    @Test
    void applicationShouldAllowOptionalServiceDiscoveryInTestBoundary() {
        ZeroServerApplication application = new ZeroServerApplication(
                new MapZeroConfig(Map.of("zero.mode", "test")),
                new InMemoryLogSink());
        InMemoryServiceDiscovery discovery = new InMemoryServiceDiscovery();

        application.start();
        discovery.start();
        discovery.register(new ServiceInstance(
                "logic-service",
                "logic-1",
                "127.0.0.1",
                6200,
                true,
                Map.of("mode", "local")));

        assertTrue(application.running());
        assertEquals(1, discovery.lookup("logic-service").size());
        discovery.stop();
        application.stop();
    }

    /**
     * 验证 starter 测试链路可以复用 zero-logic 本地逻辑夹具。
     *
     * @throws Exception 资源读取、协议生成或链路执行失败时抛出。
     */
    @Test
    void starterShouldReuseZeroLogicFixtureInSmokeFlow() throws Exception {
        ZeroServerApplication application = new ZeroServerApplication(
                new MapZeroConfig(Map.of("zero.mode", "s1-06")),
                new InMemoryLogSink());
        LocalLogicExample logicExample = new LocalLogicExample();

        application.start();
        LogicFlowResult logicResult = logicExample.runPlayerQuery("1001", "event-s2a-03", "trace-s2a-03");

        ProtocolDslDocument document = new ProtocolCodegenRunner().run(new ProtocolCodegenOptions(
                List.of(resource("codegen/dsl/standard-example.si")),
                tempDir.resolve("codegen"),
                "group.zn.zero.acceptance",
                null,
                true));

        application.stop();

        assertFalse(application.running());
        assertEquals("ZeroLogicQueryPlayerProtocol", logicResult.protocolName());
        assertEquals("event-s2a-03", logicResult.eventId());
        assertEquals("trace-s2a-03", logicResult.traceId());
        assertEquals(LaneKey.player("1001"), logicResult.laneKey());
        assertEquals(62001, logicExample.protocolDefinition().id());
        assertEquals(ProtocolDirection.CLIENT_TO_SERVER, logicExample.protocolDefinition().direction());
        assertEquals(List.of(
                "interceptor:trace-s2a-03",
                "event:62001",
                "actor:ZeroLogicQueryPlayerProtocol:1001"), logicResult.steps());
        assertEquals(6, document.protocols().size());
        assertTrue(Files.exists(tempDir.resolve(
                "codegen/group/zn/zero/acceptance/bo/impl/StandardExampleQueryPlayerEventBOImp.java")));
        assertTrue(Files.exists(tempDir.resolve(
                "codegen/group/zn/zero/acceptance/protocol/dispatch/GeneratedProtocolDispatcher.java")));
    }

    /**
     * 返回测试资源路径。
     *
     * @param name 资源名称；不可为空。
     * @return 资源路径；不可为空。
     * @throws IOException 当资源不存在或复制失败时抛出。
     */
    private Path resource(final String name) throws IOException {
        Path target = tempDir.resolve("resources").resolve(name);
        Files.createDirectories(target.getParent());
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("Missing test resource: " + name);
            }
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /**
     * 测试用生命周期组件。
     *
     * @author zn
     */
    private static final class CountingLifecycle extends AbstractLifecycle {

        /**
         * 组件名称。
         */
        private final String name;

        /**
         * 步骤记录。
         */
        private final List<String> steps;

        /**
         * 创建测试生命周期组件。
         *
         * @param name 组件名称。
         * @param steps 步骤记录。
         */
        private CountingLifecycle(final String name, final List<String> steps) {
            this.name = name;
            this.steps = steps;
        }

        /**
         * 记录启动步骤。
         */
        @Override
        protected void doStart() {
            steps.add("start:" + name);
        }

        /**
         * 记录停止步骤。
         */
        @Override
        protected void doStop() {
            steps.add("stop:" + name);
        }
    }

    /**
     * 仅实现 RPC 传输的测试组件。
     *
     * @author zn
     */
    private static final class TransportOnlyRpc implements RpcTransport {

        /**
         * 返回测试组件名称。
         *
         * @return 组件名称；不可为空；线程安全。
         */
        @Override
        public String name() {
            return "transport-only";
        }

        /**
         * 返回未使用的请求结果。
         *
         * @param request RPC 请求；不可为空。
         * @return 失败响应；不可为空；线程安全。
         */
        @Override
        public CompletionStage<RpcResponse> request(final RpcRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("not used"));
        }

        /**
         * 返回未使用的单向发送结果。
         *
         * @param request RPC 请求；不可为空。
         * @return 失败响应；不可为空；线程安全。
         */
        @Override
        public CompletionStage<Void> oneway(final RpcRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("not used"));
        }
    }
}
