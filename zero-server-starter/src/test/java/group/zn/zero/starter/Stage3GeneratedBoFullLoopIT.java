package group.zn.zero.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.cache.InMemoryCacheService;
import group.zn.zero.codegen.ProtocolCodegenOptions;
import group.zn.zero.codegen.ProtocolCodegenRunner;
import group.zn.zero.codegen.dsl.ProtocolDslDocument;
import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.data.repository.InMemoryCrudRepository;
import group.zn.zero.log.InMemoryLogSink;
import group.zn.zero.log.LogLevel;
import group.zn.zero.log.LogOperation;
import group.zn.zero.log.LogResult;
import group.zn.zero.log.LogSource;
import group.zn.zero.log.LogType;
import group.zn.zero.log.ZeroLogRecord;
import group.zn.zero.monitor.MetricDefinition;
import group.zn.zero.monitor.MetricSample;
import group.zn.zero.monitor.MonitorRuntime;
import group.zn.zero.player.LocalPlayerService;
import group.zn.zero.player.PlayerLoadRequest;
import group.zn.zero.player.PlayerLoginRequest;
import group.zn.zero.player.PlayerLoginResult;
import group.zn.zero.player.PlayerProfile;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.scene.LocalSceneService;
import group.zn.zero.scene.SceneEnterRequest;
import group.zn.zero.scene.SceneLeaveRequest;
import group.zn.zero.scene.SceneLeaveResult;
import group.zn.zero.scene.SceneMoveRequest;
import group.zn.zero.scene.SceneMoveResult;
import group.zn.zero.scene.ScenePosition;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 阶段 3 generated BO 到业务模块的完整闭环测试。
 *
 * <p>本测试覆盖 `.si -> codegen -> DTO / codec / BO / dispatcher -> zero-player /
 * zero-scene -> actor lane -> repository / cache / scene state -> log / metric / GM query`。
 *
 * @author zn
 */
class Stage3GeneratedBoFullLoopIT {

    /**
     * 登录指标名称。
     */
    private static final String LOGIN_METRIC = "zero_stage3_full_loop_login_total";

    /**
     * 玩家加载指标名称。
     */
    private static final String LOAD_PLAYER_METRIC = "zero_stage3_full_loop_player_load_total";

    /**
     * 场景移动指标名称。
     */
    private static final String SCENE_MOVE_METRIC = "zero_stage3_full_loop_scene_move_total";

    /**
     * GM 查询指标名称。
     */
    private static final String GM_QUERY_METRIC = "zero_stage3_full_loop_gm_query_total";

    /**
     * 离开场景指标名称。
     */
    private static final String LEAVE_SCENE_METRIC = "zero_stage3_full_loop_scene_leave_total";

    /**
     * 测试临时目录。
     */
    @TempDir
    private Path tempDir;

    /**
     * 验证 generated BO 可以接入正式玩家与场景基础模块。
     *
     * @throws Exception 当生成、编译或反射分发失败时抛出。
     */
    @Test
    void generatedBoShouldDrivePlayerAndSceneFoundationModules() throws Exception {
        Path dslRoot = writeDslProject();
        Path generatedRoot = tempDir.resolve("generated");
        List<String> codegenLogs = new ArrayList<>();
        ProtocolDslDocument document = new ProtocolCodegenRunner().run(new ProtocolCodegenOptions(
                List.of(dslRoot),
                generatedRoot,
                "group.zn.zero.stage3.fullloop.generated",
                dslRoot.resolve("protoId.txt"),
                false), codegenLogs::add);
        Path classesRoot = compileGeneratedSources(generatedRoot);

        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {classesRoot.toUri().toURL()},
                Thread.currentThread().getContextClassLoader())) {
            GeneratedRuntime generated = loadGeneratedRuntime(loader);
            InMemoryLogSink logSink = new InMemoryLogSink();
            MonitorRuntime monitorRuntime = MonitorRuntime.createDefault();
            GameRuntime components = LocalRuntime.builder(
                    new MapZeroConfig(Map.of(
                            ZeroRuntimeConfigKeys.ZERO_MODE, "stage3-full-loop",
                            ZeroRuntimeConfigKeys.ZERO_NAME, "stage3-generated-bo-full-loop")),
                    logSink,
                    ZeroRuntimeExecutors.localPrototype("zero-stage3-full-loop", 2))
                    .replace(LocalRuntimeCapabilities.MONITOR_RUNTIME, monitorRuntime)
                    .build();
            ZeroServerApplication application = new ZeroServerApplication(components);
            InMemoryCrudRepository<Long, PlayerProfile> playerRepository = new InMemoryCrudRepository<>();
            InMemoryCacheService<Long, PlayerProfile> playerCache = new InMemoryCacheService<>();
            FullLoopResults results = new FullLoopResults();

            application.start();
            try (LocalPlayerService playerService = new LocalPlayerService(
                    components.require(LocalRuntimeCapabilities.ACTOR_SCHEDULER),
                    request -> 1001L,
                    playerRepository,
                    playerCache);
                    LocalSceneService sceneService = new LocalSceneService(
                            components.require(LocalRuntimeCapabilities.ACTOR_SCHEDULER))) {
                registerMetrics(components.require(LocalRuntimeCapabilities.MONITOR_RUNTIME));
                Object dispatcher = registerGeneratedBos(generated, components, playerService, sceneService, results);
                dispatchScenario(generated, dispatcher);
                assertFullLoop(
                        document,
                        codegenLogs,
                        results,
                        playerRepository,
                        playerCache,
                        sceneService,
                        logSink,
                        components.require(LocalRuntimeCapabilities.MONITOR_RUNTIME));
            } finally {
                application.stop();
            }
        }
        System.out.println("zero-local-integration=ok|flow=generated-bo-full-loop");
    }

    private void dispatchScenario(final GeneratedRuntime generated, final Object dispatcher) throws Exception {
        dispatch(generated.dispatchMethod(), dispatcher, generated.login(), request ->
                set(request, Map.of(
                        "accountId", "guest-1001",
                        "token", "token-local",
                        "traceId", "trace-full-login")));
        dispatch(generated.dispatchMethod(), dispatcher, generated.loadPlayer(), request ->
                set(request, Map.of("uid", 1001L, "traceId", "trace-full-load")));
        dispatch(generated.dispatchMethod(), dispatcher, generated.enterScene(), request ->
                set(request, Map.of(
                        "uid", 1001L,
                        "sceneId", "scene-1",
                        "traceId", "trace-full-enter")));
        dispatch(generated.dispatchMethod(), dispatcher, generated.move(), request ->
                set(request, Map.of(
                        "uid", 1001L,
                        "sceneId", "scene-1",
                        "x", 7,
                        "y", 11,
                        "traceId", "trace-full-move")));
        dispatch(generated.dispatchMethod(), dispatcher, generated.gmQueryPlayer(), request ->
                set(request, Map.of(
                        "uid", 1001L,
                        "operatorId", "gm-local",
                        "traceId", "trace-full-gm-player")));
        dispatch(generated.dispatchMethod(), dispatcher, generated.gmQueryScene(), request ->
                set(request, Map.of(
                        "sceneId", "scene-1",
                        "traceId", "trace-full-gm-scene-before")));
        dispatch(generated.dispatchMethod(), dispatcher, generated.leaveScene(), request ->
                set(request, Map.of(
                        "uid", 1001L,
                        "sceneId", "scene-1",
                        "traceId", "trace-full-leave")));
        dispatch(generated.dispatchMethod(), dispatcher, generated.gmQueryScene(), request ->
                set(request, Map.of(
                        "sceneId", "scene-1",
                        "traceId", "trace-full-gm-scene-after")));
    }

    private void assertFullLoop(
            final ProtocolDslDocument document,
            final List<String> codegenLogs,
            final FullLoopResults results,
            final InMemoryCrudRepository<Long, PlayerProfile> playerRepository,
            final InMemoryCacheService<Long, PlayerProfile> playerCache,
            final LocalSceneService sceneService,
            final InMemoryLogSink logSink,
            final MonitorRuntime monitorRuntime) {
        PlayerProfile expectedProfile = new PlayerProfile(1001L, 1L, "player-1001", true);
        assertEquals(7, document.protocols().size());
        assertTrue(codegenLogs.stream().anyMatch(item -> item.contains("Generating protocol sources")));
        assertEquals(new PlayerLoginResult("guest-1001", 1001L, "trace-full-login"), results.loginResult());
        assertEquals(expectedProfile, results.loadedProfile());
        assertEquals(expectedProfile, playerRepository.findById(1001L).toCompletableFuture()
                .join()
                .orElseThrow());
        assertEquals(expectedProfile, playerCache.get(1001L).toCompletableFuture().join().orElseThrow());
        assertEquals(new ScenePosition(7, 11), results.moveResult().currentState().position());
        assertTrue(results.moveResult().previousState().isPresent());
        assertEquals("uid=1001|name=player-1001|online=true", results.gmPlayerResult());
        assertEquals("scene=scene-1|entities=1|first=1001@7,11", results.gmSceneBeforeLeave());
        assertEquals("scene=scene-1|entities=0", results.gmSceneAfterLeave());
        assertTrue(results.leaveResult().removed());
        assertEquals(new ScenePosition(7, 11), results.leaveResult().leftState().orElseThrow().position());
        assertFalse(sceneService.queryEntity("scene-1", 1001L, "trace-verify")
                .toCompletableFuture()
                .join()
                .isPresent());
        assertLog(logSink, "trace-full-login", "stage3 full loop login completed");
        assertLog(logSink, "trace-full-load", "stage3 full loop player loaded");
        assertLog(logSink, "trace-full-enter", "stage3 full loop scene entered");
        assertLog(logSink, "trace-full-move", "stage3 full loop scene moved");
        assertLog(logSink, "trace-full-gm-player", "stage3 full loop gm player queried");
        assertLog(logSink, "trace-full-leave", "stage3 full loop scene left");
        assertMetric(monitorRuntime, LOGIN_METRIC, "login");
        assertMetric(monitorRuntime, LOAD_PLAYER_METRIC, "load-player");
        assertMetric(monitorRuntime, SCENE_MOVE_METRIC, "move");
        assertMetric(monitorRuntime, GM_QUERY_METRIC, "gm-query-player");
        assertMetric(monitorRuntime, LEAVE_SCENE_METRIC, "leave-scene");
    }

    private Path writeDslProject() throws IOException {
        Path dslRoot = tempDir.resolve("dsl");
        Files.createDirectories(dslRoot);
        Files.writeString(dslRoot.resolve("Prototype.si"), """
                # Stage 3 generated BO full loop.

                client_to_server:
                  login(String accountId, String token, String traceId); // login request.
                  loadPlayer(long uid, String traceId); // load player online data.
                  enterScene(long uid, String sceneId, String traceId); // enter default scene.
                  move(long uid, String sceneId, int x, int y, String traceId); // move in scene.
                  gmQueryPlayer(long uid, String operatorId, String traceId); // query player by GM.
                  gmQueryScene(String sceneId, String traceId); // query scene entities by GM.
                  leaveScene(long uid, String sceneId, String traceId); // leave scene.
                """, StandardCharsets.UTF_8);
        Files.writeString(dslRoot.resolve("protoId.txt"), "Prototype 70101 70200\n", StandardCharsets.UTF_8);
        return dslRoot;
    }

    private Path compileGeneratedSources(final Path sourceRoot) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler is required for generated protocol full loop test");
        Path classesRoot = tempDir.resolve("generated-classes");
        Files.createDirectories(classesRoot);
        List<Path> sources;
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            sources = stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
        assertTrue(!sources.isEmpty(), "generated sources must not be empty");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics,
                Locale.ROOT,
                StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classesRoot));
            Iterable<? extends JavaFileObject> files = fileManager.getJavaFileObjectsFromPaths(sources);
            List<String> options = List.of("-encoding", "UTF-8", "-classpath", System.getProperty("java.class.path"));
            Boolean success = compiler.getTask(null, fileManager, diagnostics, options, null, files).call();
            assertTrue(Boolean.TRUE.equals(success), diagnostics(diagnostics));
        }
        return classesRoot;
    }

    private String diagnostics(final DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder builder = new StringBuilder("generated source compilation failed");
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            builder.append(System.lineSeparator())
                    .append(diagnostic.getKind())
                    .append(":")
                    .append(diagnostic.getLineNumber())
                    .append(" ")
                    .append(diagnostic.getMessage(Locale.ROOT));
        }
        return builder.toString();
    }

    private GeneratedRuntime loadGeneratedRuntime(final ClassLoader loader) throws Exception {
        Class<?> dispatcherClass = loader.loadClass(
                "group.zn.zero.stage3.fullloop.generated.protocol.dispatch.GeneratedProtocolDispatcher");
        Object dispatcher = dispatcherClass.getConstructor().newInstance();
        Class<?> protocolIds = loader.loadClass("group.zn.zero.stage3.fullloop.generated.protocol.ProtocolIds");
        Method dispatchMethod = dispatcherClass.getMethod("dispatch", int.class, byte[].class);
        return new GeneratedRuntime(
                dispatcher,
                dispatchMethod,
                endpoint(loader, dispatcherClass, protocolIds,
                        "PrototypeLogin", "PROTOTYPE_LOGIN_PROTOCOL"),
                endpoint(loader, dispatcherClass, protocolIds,
                        "PrototypeLoadPlayer", "PROTOTYPE_LOAD_PLAYER_PROTOCOL"),
                endpoint(loader, dispatcherClass, protocolIds,
                        "PrototypeEnterScene", "PROTOTYPE_ENTER_SCENE_PROTOCOL"),
                endpoint(loader, dispatcherClass, protocolIds,
                        "PrototypeMove", "PROTOTYPE_MOVE_PROTOCOL"),
                endpoint(loader, dispatcherClass, protocolIds,
                        "PrototypeGmQueryPlayer", "PROTOTYPE_GM_QUERY_PLAYER_PROTOCOL"),
                endpoint(loader, dispatcherClass, protocolIds,
                        "PrototypeGmQueryScene", "PROTOTYPE_GM_QUERY_SCENE_PROTOCOL"),
                endpoint(loader, dispatcherClass, protocolIds,
                        "PrototypeLeaveScene", "PROTOTYPE_LEAVE_SCENE_PROTOCOL"));
    }

    private Endpoint endpoint(
            final ClassLoader loader,
            final Class<?> dispatcherClass,
            final Class<?> protocolIds,
            final String stem,
            final String fieldName) throws Exception {
        Class<?> boClass = loader.loadClass("group.zn.zero.stage3.fullloop.generated.bo." + stem + "EventBO");
        return new Endpoint(
                boClass,
                loader.loadClass("group.zn.zero.stage3.fullloop.generated.dto." + stem + "ProtocolDTO"),
                loader.loadClass("group.zn.zero.stage3.fullloop.generated.dto.codec." + stem + "ProtocolDTOCodec"),
                protocolIds.getField(fieldName).getInt(null),
                dispatcherClass.getMethod("register" + stem + "EventBO", boClass));
    }

    private void registerMetrics(final MonitorRuntime monitorRuntime) {
        monitorRuntime.registry().register(new MetricDefinition(
                LOGIN_METRIC, "stage3 full loop login", "count", List.of("operation")));
        monitorRuntime.registry().register(new MetricDefinition(
                LOAD_PLAYER_METRIC,
                "stage3 full loop player load",
                "count",
                List.of("operation")));
        monitorRuntime.registry().register(new MetricDefinition(
                SCENE_MOVE_METRIC,
                "stage3 full loop scene move",
                "count",
                List.of("operation")));
        monitorRuntime.registry().register(new MetricDefinition(
                GM_QUERY_METRIC,
                "stage3 full loop gm query",
                "count",
                List.of("operation")));
        monitorRuntime.registry().register(new MetricDefinition(
                LEAVE_SCENE_METRIC,
                "stage3 full loop scene leave",
                "count",
                List.of("operation")));
    }

    private Object registerGeneratedBos(
            final GeneratedRuntime generated,
            final GameRuntime components,
            final LocalPlayerService playerService,
            final LocalSceneService sceneService,
            final FullLoopResults results) throws Exception {
        generated.login().registerMethod().invoke(generated.dispatcher(), proxy(generated.login().boClass(), request -> {
            PlayerLoginResult result = playerService.login(new PlayerLoginRequest(
                    stringField(request, "accountId"),
                    stringField(request, "token"),
                    stringField(request, "traceId"))).toCompletableFuture().join();
            results.loginResult(result);
            appendLog(components, result.traceId(), "stage3 full loop login completed", "login");
            recordMetric(components, LOGIN_METRIC, "login");
        }));
        generated.loadPlayer().registerMethod().invoke(
                generated.dispatcher(),
                proxy(generated.loadPlayer().boClass(), request -> {
                    PlayerProfile profile = playerService.loadPlayer(new PlayerLoadRequest(
                            longField(request, "uid"),
                            stringField(request, "traceId"))).toCompletableFuture().join();
                    results.loadedProfile(profile);
                    appendLog(components, stringField(request, "traceId"), "stage3 full loop player loaded", "load-player");
                    recordMetric(components, LOAD_PLAYER_METRIC, "load-player");
                }));
        generated.enterScene().registerMethod().invoke(
                generated.dispatcher(),
                proxy(generated.enterScene().boClass(), request -> {
                    sceneService.enterScene(new SceneEnterRequest(
                            longField(request, "uid"),
                            stringField(request, "sceneId"),
                            stringField(request, "traceId"))).toCompletableFuture().join();
                    appendLog(components, stringField(request, "traceId"), "stage3 full loop scene entered", "enter-scene");
                }));
        generated.move().registerMethod().invoke(
                generated.dispatcher(),
                proxy(generated.move().boClass(), request -> {
                    SceneMoveResult result = sceneService.moveWithResult(new SceneMoveRequest(
                            longField(request, "uid"),
                            stringField(request, "sceneId"),
                            new ScenePosition(intField(request, "x"), intField(request, "y")),
                            stringField(request, "traceId"))).toCompletableFuture().join();
                    results.moveResult(result);
                    appendLog(components, stringField(request, "traceId"), "stage3 full loop scene moved", "move");
                    recordMetric(components, SCENE_MOVE_METRIC, "move");
                }));
        generated.gmQueryPlayer().registerMethod().invoke(
                generated.dispatcher(),
                proxy(generated.gmQueryPlayer().boClass(), request -> {
                    PlayerProfile profile = playerService.queryPlayer(
                            longField(request, "uid"),
                            stringField(request, "traceId")).toCompletableFuture().join().orElseThrow();
                    results.gmPlayerResult("uid=" + profile.uid()
                            + "|name=" + profile.name()
                            + "|online=" + profile.online());
                    appendLog(components, stringField(request, "traceId"),
                            "stage3 full loop gm player queried", "gm-query-player");
                    recordMetric(components, GM_QUERY_METRIC, "gm-query-player");
                }));
        generated.gmQueryScene().registerMethod().invoke(
                generated.dispatcher(),
                proxy(generated.gmQueryScene().boClass(), request -> {
                    List<group.zn.zero.scene.SceneEntityState> entities = sceneService.listEntities(
                            stringField(request, "sceneId"),
                            stringField(request, "traceId")).toCompletableFuture().join();
                    String value = sceneSummary(stringField(request, "sceneId"), entities);
                    if (entities.isEmpty()) {
                        results.gmSceneAfterLeave(value);
                    } else {
                        results.gmSceneBeforeLeave(value);
                    }
                    recordMetric(components, GM_QUERY_METRIC, "gm-query-scene");
                }));
        generated.leaveScene().registerMethod().invoke(
                generated.dispatcher(),
                proxy(generated.leaveScene().boClass(), request -> {
                    SceneLeaveResult result = sceneService.leaveScene(new SceneLeaveRequest(
                            longField(request, "uid"),
                            stringField(request, "sceneId"),
                            stringField(request, "traceId"))).toCompletableFuture().join();
                    results.leaveResult(result);
                    appendLog(components, stringField(request, "traceId"), "stage3 full loop scene left", "leave-scene");
                    recordMetric(components, LEAVE_SCENE_METRIC, "leave-scene");
                }));
        return generated.dispatcher();
    }

    private String sceneSummary(
            final String sceneId,
            final List<group.zn.zero.scene.SceneEntityState> entities) {
        if (entities.isEmpty()) {
            return "scene=" + sceneId + "|entities=0";
        }
        group.zn.zero.scene.SceneEntityState first = entities.getFirst();
        return "scene=" + sceneId
                + "|entities=" + entities.size()
                + "|first=" + first.uid()
                + "@" + first.position().x()
                + "," + first.position().y();
    }

    private Object proxy(final Class<?> boClass, final Consumer<Object> requestConsumer) {
        return Proxy.newProxyInstance(
                boClass.getClassLoader(),
                new Class<?>[] {boClass},
                (proxy, method, args) -> {
                    requestConsumer.accept(args[0]);
                    return null;
                });
    }

    private void dispatch(
            final Method dispatchMethod,
            final Object dispatcher,
            final Endpoint endpoint,
            final Consumer<Object> requestWriter) throws Exception {
        Object request = endpoint.requestClass().getConstructor().newInstance();
        requestWriter.accept(request);
        byte[] payload = encode(endpoint.codecClass(), endpoint.requestClass(), request);
        assertTrue((boolean) dispatchMethod.invoke(dispatcher, endpoint.protocolId(), payload));
    }

    private byte[] encode(
            final Class<?> codecClass,
            final Class<?> requestClass,
            final Object request) throws Exception {
        Object codec = codecClass.getField("INSTANCE").get(null);
        Method write = codecClass.getMethod("write", ZeroWriter.class, requestClass);
        try (ZeroWriter writer = new ZeroWriter()) {
            write.invoke(codec, writer, request);
            return writer.toByteArray();
        }
    }

    private void set(final Object target, final Map<String, Object> values) {
        values.forEach((key, value) -> {
            try {
                target.getClass().getField(key).set(target, value);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("set generated field failed: " + key, ex);
            }
        });
    }

    private String stringField(final Object target, final String name) {
        try {
            return (String) target.getClass().getField(name).get(target);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("read generated string field failed: " + name, ex);
        }
    }

    private long longField(final Object target, final String name) {
        try {
            return target.getClass().getField(name).getLong(target);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("read generated long field failed: " + name, ex);
        }
    }

    private int intField(final Object target, final String name) {
        try {
            return target.getClass().getField(name).getInt(target);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("read generated int field failed: " + name, ex);
        }
    }

    private void appendLog(
            final GameRuntime components,
            final String traceId,
            final String message,
            final String operation) {
        components.require(LocalRuntimeCapabilities.LOG_APPENDER).append(ZeroLogRecord.create(
                Instant.now(),
                LogLevel.INFO,
                LogType.BUSINESS,
                new LogSource("stage3-full-loop", "local-test", "zero-server-starter"),
                new LogOperation(operation, LogResult.SUCCESS, null),
                traceId,
                message,
                Map.of()));
    }

    private void recordMetric(
            final GameRuntime components,
            final String metricName,
            final String operation) {
        components.require(LocalRuntimeCapabilities.MONITOR_RUNTIME).registry().record(new MetricSample(
                metricName,
                1.0D,
                Map.of("operation", operation),
                Instant.now()));
    }

    private void assertLog(final InMemoryLogSink logSink, final String traceId, final String message) {
        assertTrue(logSink.records().stream()
                .anyMatch(record -> traceId.equals(record.traceId()) && message.equals(record.message())));
    }

    private void assertMetric(final MonitorRuntime monitorRuntime, final String name, final String operation) {
        assertTrue(monitorRuntime.registry().samples().stream()
                .anyMatch(sample -> name.equals(sample.name())
                        && operation.equals(sample.labels().get("operation"))));
    }

    /**
     * 生成端点反射信息。
     *
     * @param boClass BO 接口类型。
     * @param requestClass 请求 DTO 类型。
     * @param codecClass 请求 codec 类型。
     * @param protocolId 协议号。
     * @param registerMethod 分发器注册方法。
     */
    private record Endpoint(
            Class<?> boClass,
            Class<?> requestClass,
            Class<?> codecClass,
            int protocolId,
            Method registerMethod) {
    }

    /**
     * 生成运行时反射信息。
     *
     * @param dispatcher 分发器实例。
     * @param dispatchMethod 分发方法。
     * @param login 登录端点。
     * @param loadPlayer 玩家加载端点。
     * @param enterScene 场景进入端点。
     * @param move 移动端点。
     * @param gmQueryPlayer GM 查询玩家端点。
     * @param gmQueryScene GM 查询场景端点。
     * @param leaveScene 离开场景端点。
     */
    private record GeneratedRuntime(
            Object dispatcher,
            Method dispatchMethod,
            Endpoint login,
            Endpoint loadPlayer,
            Endpoint enterScene,
            Endpoint move,
            Endpoint gmQueryPlayer,
            Endpoint gmQueryScene,
            Endpoint leaveScene) {
    }

    /**
     * 完整闭环结果。
     *
     * @author zn
     */
    private static final class FullLoopResults {

        /**
         * 登录结果。
         */
        private final AtomicReference<PlayerLoginResult> loginResult = new AtomicReference<>();

        /**
         * 加载后的玩家档案。
         */
        private final AtomicReference<PlayerProfile> loadedProfile = new AtomicReference<>();

        /**
         * 移动结果。
         */
        private final AtomicReference<SceneMoveResult> moveResult = new AtomicReference<>();

        /**
         * 离开场景结果。
         */
        private final AtomicReference<SceneLeaveResult> leaveResult = new AtomicReference<>();

        /**
         * GM 玩家查询结果。
         */
        private final AtomicReference<String> gmPlayerResult = new AtomicReference<>("");

        /**
         * 离开前 GM 场景查询结果。
         */
        private final AtomicReference<String> gmSceneBeforeLeave = new AtomicReference<>("");

        /**
         * 离开后 GM 场景查询结果。
         */
        private final AtomicReference<String> gmSceneAfterLeave = new AtomicReference<>("");

        /**
         * 写入登录结果。
         *
         * @param value 登录结果；不可为空。
         */
        void loginResult(final PlayerLoginResult value) {
            loginResult.set(Objects.requireNonNull(value, "value"));
        }

        /**
         * 返回登录结果。
         *
         * @return 登录结果；不可为空。
         */
        PlayerLoginResult loginResult() {
            return loginResult.get();
        }

        /**
         * 写入玩家档案。
         *
         * @param value 玩家档案；不可为空。
         */
        void loadedProfile(final PlayerProfile value) {
            loadedProfile.set(Objects.requireNonNull(value, "value"));
        }

        /**
         * 返回玩家档案。
         *
         * @return 玩家档案；不可为空。
         */
        PlayerProfile loadedProfile() {
            return loadedProfile.get();
        }

        /**
         * 写入移动结果。
         *
         * @param value 移动结果；不可为空。
         */
        void moveResult(final SceneMoveResult value) {
            moveResult.set(Objects.requireNonNull(value, "value"));
        }

        /**
         * 返回移动结果。
         *
         * @return 移动结果；不可为空。
         */
        SceneMoveResult moveResult() {
            return moveResult.get();
        }

        /**
         * 写入离开场景结果。
         *
         * @param value 离开场景结果；不可为空。
         */
        void leaveResult(final SceneLeaveResult value) {
            leaveResult.set(Objects.requireNonNull(value, "value"));
        }

        /**
         * 返回离开场景结果。
         *
         * @return 离开场景结果；不可为空。
         */
        SceneLeaveResult leaveResult() {
            return leaveResult.get();
        }

        /**
         * 写入 GM 玩家查询结果。
         *
         * @param value 查询结果；不可为空。
         */
        void gmPlayerResult(final String value) {
            gmPlayerResult.set(Objects.requireNonNull(value, "value"));
        }

        /**
         * 返回 GM 玩家查询结果。
         *
         * @return 查询结果；不可为空。
         */
        String gmPlayerResult() {
            return gmPlayerResult.get();
        }

        /**
         * 写入离开前 GM 场景查询结果。
         *
         * @param value 查询结果；不可为空。
         */
        void gmSceneBeforeLeave(final String value) {
            gmSceneBeforeLeave.set(Objects.requireNonNull(value, "value"));
        }

        /**
         * 返回离开前 GM 场景查询结果。
         *
         * @return 查询结果；不可为空。
         */
        String gmSceneBeforeLeave() {
            return gmSceneBeforeLeave.get();
        }

        /**
         * 写入离开后 GM 场景查询结果。
         *
         * @param value 查询结果；不可为空。
         */
        void gmSceneAfterLeave(final String value) {
            gmSceneAfterLeave.set(Objects.requireNonNull(value, "value"));
        }

        /**
         * 返回离开后 GM 场景查询结果。
         *
         * @return 查询结果；不可为空。
         */
        String gmSceneAfterLeave() {
            return gmSceneAfterLeave.get();
        }
    }
}
