package group.zn.zero.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.codegen.dsl.ProtocolDslDocument;
import group.zn.zero.codegen.ProtocolCodegenOptions;
import group.zn.zero.codegen.ProtocolCodegenRunner;
import group.zn.zero.core.config.MapZeroConfig;
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
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.runtime.actor.ActorRuntime;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeConfigKeys;
import group.zn.zero.runtime.log.LogRuntime;
import group.zn.zero.runtime.monitor.MonitorRuntimeComponent;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

/**
 * 阶段 3 本地原型开箱即用 smoke 测试。
 *
 * <p>本测试仅在 test scope 内验证协议 DSL 生成物、starter 本地装配、Actor lane、日志和指标
 * 可以形成最小业务闭环；正式业务模块接入由 `Stage3GeneratedBoFullLoopIT` 覆盖。
 *
 * @author zn
 */
class Stage3LocalPrototypeSmokeTest {

    /**
     * 登录指标名称。
     */
    private static final String LOGIN_METRIC = "zero_stage3_login_total";

    /**
     * 玩家加载指标名称。
     */
    private static final String LOAD_PLAYER_METRIC = "zero_stage3_player_load_total";

    /**
     * 场景移动指标名称。
     */
    private static final String SCENE_MOVE_METRIC = "zero_stage3_scene_move_total";

    /**
     * GM 查询指标名称。
     */
    private static final String GM_QUERY_METRIC = "zero_stage3_gm_query_total";

    /**
     * 测试临时目录。
     */
    @TempDir
    private Path tempDir;

    /**
     * 验证生成 BO 可以驱动本地无 Docker 阶段 3 原型闭环。
     *
     * @throws Exception 当生成、编译或反射分发失败时抛出。
     */
    @Test
    void generatedBoShouldDriveLocalPrototypeWithoutDocker() throws Exception {
        Path dslRoot = writeDslProject();
        Path generatedRoot = tempDir.resolve("generated");
        List<String> codegenLogs = new ArrayList<>();

        ProtocolDslDocument document = new ProtocolCodegenRunner().run(new ProtocolCodegenOptions(
                List.of(dslRoot),
                generatedRoot,
                "group.zn.zero.stage3.generated",
                dslRoot.resolve("protoId.txt"),
                false), codegenLogs::add);
        Path classesRoot = compileGeneratedSources(generatedRoot);

        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {classesRoot.toUri().toURL()},
                Thread.currentThread().getContextClassLoader())) {
            GeneratedRuntime generated = loadGeneratedRuntime(loader);
            InMemoryLogSink baseLogSink = new InMemoryLogSink();
            MonitorRuntime monitorRuntime = MonitorRuntime.createDefault();
            GameRuntime components = LocalRuntime.builder(new MapZeroConfig(Map.of(
                            ZeroRuntimeConfigKeys.ZERO_MODE, "stage3-smoke",
                            ZeroRuntimeConfigKeys.ZERO_NAME, "stage3-local-prototype")), baseLogSink)
                    .replace(MonitorRuntimeComponent.MONITOR_RUNTIME, monitorRuntime)
                    .build();
            ZeroServerApplication application = new ZeroServerApplication(components);
            Stage3PrototypeState state = new Stage3PrototypeState();

            try {
                application.start();
                registerMetrics(components.require(MonitorRuntimeComponent.MONITOR_RUNTIME));
                registerActorHandlers(components, state);
                Object dispatcher = registerGeneratedBos(generated, components, state);

                dispatch(generated.dispatchMethod(), dispatcher, generated.login(), request ->
                        set(request, Map.of(
                                "accountId", "guest-1001",
                                "token", "token-local",
                                "traceId", "trace-stage3-login")));
                dispatch(generated.dispatchMethod(), dispatcher, generated.loadPlayer(), request ->
                        set(request, Map.of(
                                "uid", 1001L,
                                "traceId", "trace-stage3-load")));
                dispatch(generated.dispatchMethod(), dispatcher, generated.enterScene(), request ->
                        set(request, Map.of(
                                "uid", 1001L,
                                "sceneId", "scene-1",
                                "traceId", "trace-stage3-enter")));
                dispatch(generated.dispatchMethod(), dispatcher, generated.move(), request ->
                        set(request, Map.of(
                                "uid", 1001L,
                                "sceneId", "scene-1",
                                "x", 7,
                                "y", 11,
                                "traceId", "trace-stage3-move")));
                dispatch(generated.dispatchMethod(), dispatcher, generated.gmQueryPlayer(), request ->
                        set(request, Map.of(
                                "uid", 1001L,
                                "operatorId", "gm-local",
                                "traceId", "trace-stage3-gm")));

                assertEquals(5, document.protocols().size());
                assertTrue(codegenLogs.stream().anyMatch(item -> item.contains("Generating protocol sources")));
                assertEquals(1001L, state.sessionUid("guest-1001"));
                assertEquals(new PlayerProfile(1001L, "player-1001", true), state.player(1001L));
                assertEquals(new Position(7, 11), state.position("scene-1", 1001L));
                assertEquals("uid=1001|name=player-1001|online=true", state.gmResult());
                assertEquals(List.of(
                        LaneKey.session("guest-1001"),
                        LaneKey.player("1001"),
                        LaneKey.scene("scene-1"),
                        LaneKey.scene("scene-1"),
                        LaneKey.player("1001")), state.lanes());
                assertLog(baseLogSink, "trace-stage3-login", "stage3 login completed");
                assertLog(baseLogSink, "trace-stage3-load", "stage3 player loaded");
                assertLog(baseLogSink, "trace-stage3-enter", "stage3 scene entered");
                assertLog(baseLogSink, "trace-stage3-move", "stage3 scene moved");
                assertLog(baseLogSink, "trace-stage3-gm", "stage3 gm query completed");
                assertMetric(components.require(MonitorRuntimeComponent.MONITOR_RUNTIME), LOGIN_METRIC, "login");
                assertMetric(components.require(MonitorRuntimeComponent.MONITOR_RUNTIME),
                        LOAD_PLAYER_METRIC, "load-player");
                assertMetric(components.require(MonitorRuntimeComponent.MONITOR_RUNTIME),
                        SCENE_MOVE_METRIC, "move");
                assertMetric(components.require(MonitorRuntimeComponent.MONITOR_RUNTIME),
                        GM_QUERY_METRIC, "gm-query");
            } finally {
                if (application.running()) {
                    application.stop();
                }
            }
        }
    }

    private Path writeDslProject() throws IOException {
        Path dslRoot = tempDir.resolve("dsl");
        Files.createDirectories(dslRoot);
        Files.writeString(dslRoot.resolve("Prototype.si"), """
                # Stage 3 local no-Docker prototype.

                client_to_server:
                  login(String accountId, String token, String traceId); // login request.
                  loadPlayer(long uid, String traceId); // load player online data.
                  enterScene(long uid, String sceneId, String traceId); // enter default scene.
                  move(long uid, String sceneId, int x, int y, String traceId); // move in scene.
                  gmQueryPlayer(long uid, String operatorId, String traceId); // query player by GM.
                """, StandardCharsets.UTF_8);
        Files.writeString(dslRoot.resolve("protoId.txt"), "Prototype 70000 70100\n", StandardCharsets.UTF_8);
        return dslRoot;
    }

    private Path compileGeneratedSources(final Path sourceRoot) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler is required for generated protocol smoke test");
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
                "group.zn.zero.stage3.generated.protocol.dispatch.GeneratedProtocolDispatcher");
        Object dispatcher = dispatcherClass.getConstructor().newInstance();
        Class<?> protocolIds = loader.loadClass("group.zn.zero.stage3.generated.protocol.ProtocolIds");
        Method dispatchMethod = dispatcherClass.getMethod("dispatch", int.class, byte[].class);
        return new GeneratedRuntime(
                dispatcher,
                dispatchMethod,
                new Endpoint(
                        loader.loadClass("group.zn.zero.stage3.generated.bo.PrototypeLoginEventBO"),
                        loader.loadClass("group.zn.zero.stage3.generated.dto.PrototypeLoginProtocolDTO"),
                        loader.loadClass("group.zn.zero.stage3.generated.dto.codec.PrototypeLoginProtocolDTOCodec"),
                        protocolIds.getField("PROTOTYPE_LOGIN_PROTOCOL").getInt(null),
                        dispatcherClass.getMethod(
                                "registerPrototypeLoginEventBO",
                                loader.loadClass("group.zn.zero.stage3.generated.bo.PrototypeLoginEventBO"))),
                new Endpoint(
                        loader.loadClass("group.zn.zero.stage3.generated.bo.PrototypeLoadPlayerEventBO"),
                        loader.loadClass("group.zn.zero.stage3.generated.dto.PrototypeLoadPlayerProtocolDTO"),
                        loader.loadClass(
                                "group.zn.zero.stage3.generated.dto.codec.PrototypeLoadPlayerProtocolDTOCodec"),
                        protocolIds.getField("PROTOTYPE_LOAD_PLAYER_PROTOCOL").getInt(null),
                        dispatcherClass.getMethod(
                                "registerPrototypeLoadPlayerEventBO",
                                loader.loadClass("group.zn.zero.stage3.generated.bo.PrototypeLoadPlayerEventBO"))),
                new Endpoint(
                        loader.loadClass("group.zn.zero.stage3.generated.bo.PrototypeEnterSceneEventBO"),
                        loader.loadClass("group.zn.zero.stage3.generated.dto.PrototypeEnterSceneProtocolDTO"),
                        loader.loadClass(
                                "group.zn.zero.stage3.generated.dto.codec.PrototypeEnterSceneProtocolDTOCodec"),
                        protocolIds.getField("PROTOTYPE_ENTER_SCENE_PROTOCOL").getInt(null),
                        dispatcherClass.getMethod(
                                "registerPrototypeEnterSceneEventBO",
                                loader.loadClass("group.zn.zero.stage3.generated.bo.PrototypeEnterSceneEventBO"))),
                new Endpoint(
                        loader.loadClass("group.zn.zero.stage3.generated.bo.PrototypeMoveEventBO"),
                        loader.loadClass("group.zn.zero.stage3.generated.dto.PrototypeMoveProtocolDTO"),
                        loader.loadClass("group.zn.zero.stage3.generated.dto.codec.PrototypeMoveProtocolDTOCodec"),
                        protocolIds.getField("PROTOTYPE_MOVE_PROTOCOL").getInt(null),
                        dispatcherClass.getMethod(
                                "registerPrototypeMoveEventBO",
                                loader.loadClass("group.zn.zero.stage3.generated.bo.PrototypeMoveEventBO"))),
                new Endpoint(
                        loader.loadClass("group.zn.zero.stage3.generated.bo.PrototypeGmQueryPlayerEventBO"),
                        loader.loadClass("group.zn.zero.stage3.generated.dto.PrototypeGmQueryPlayerProtocolDTO"),
                        loader.loadClass(
                                "group.zn.zero.stage3.generated.dto.codec.PrototypeGmQueryPlayerProtocolDTOCodec"),
                        protocolIds.getField("PROTOTYPE_GM_QUERY_PLAYER_PROTOCOL").getInt(null),
                        dispatcherClass.getMethod(
                                "registerPrototypeGmQueryPlayerEventBO",
                                loader.loadClass("group.zn.zero.stage3.generated.bo.PrototypeGmQueryPlayerEventBO"))));
    }

    private void registerMetrics(final MonitorRuntime monitorRuntime) {
        monitorRuntime.registry().register(new MetricDefinition(
                LOGIN_METRIC, "stage3 login count", "count", List.of("operation")));
        monitorRuntime.registry().register(new MetricDefinition(
                LOAD_PLAYER_METRIC,
                "stage3 player load count",
                "count",
                List.of("operation")));
        monitorRuntime.registry().register(new MetricDefinition(
                SCENE_MOVE_METRIC, "stage3 scene move count", "count", List.of("operation")));
        monitorRuntime.registry().register(new MetricDefinition(
                GM_QUERY_METRIC, "stage3 gm query count", "count", List.of("operation")));
    }

    private void registerActorHandlers(final GameRuntime components, final Stage3PrototypeState state) {
        components.require(ActorRuntime.ACTOR_SCHEDULER)
                .register(LoginCommand.class, ActorHandler.sync((context, message) -> {
            LoginCommand command = (LoginCommand) message.payload();
            assertEquals(LaneKey.session(command.accountId()), context.laneKey());
            state.rememberLane(context.laneKey());
            state.login(command.accountId(), command.uid());
            appendLog(components, command.traceId(), "stage3 login completed", "login");
            recordMetric(components, LOGIN_METRIC, "login");
        }));
        components.require(ActorRuntime.ACTOR_SCHEDULER)
                .register(LoadPlayerCommand.class, ActorHandler.sync((context, message) -> {
            LoadPlayerCommand command = (LoadPlayerCommand) message.payload();
            assertEquals(LaneKey.player(Long.toString(command.uid())), context.laneKey());
            state.rememberLane(context.laneKey());
            state.loadPlayer(new PlayerProfile(command.uid(), "player-" + command.uid(), true));
            appendLog(components, command.traceId(), "stage3 player loaded", "load-player");
            recordMetric(components, LOAD_PLAYER_METRIC, "load-player");
        }));
        components.require(ActorRuntime.ACTOR_SCHEDULER)
                .register(EnterSceneCommand.class, ActorHandler.sync((context, message) -> {
            EnterSceneCommand command = (EnterSceneCommand) message.payload();
            assertEquals(LaneKey.scene(command.sceneId()), context.laneKey());
            state.rememberLane(context.laneKey());
            state.enterScene(command.sceneId(), command.uid(), new Position(0, 0));
            appendLog(components, command.traceId(), "stage3 scene entered", "enter-scene");
        }));
        components.require(ActorRuntime.ACTOR_SCHEDULER)
                .register(MoveCommand.class, ActorHandler.sync((context, message) -> {
            MoveCommand command = (MoveCommand) message.payload();
            assertEquals(LaneKey.scene(command.sceneId()), context.laneKey());
            state.rememberLane(context.laneKey());
            state.move(command.sceneId(), command.uid(), new Position(command.x(), command.y()));
            appendLog(components, command.traceId(), "stage3 scene moved", "move");
            recordMetric(components, SCENE_MOVE_METRIC, "move");
        }));
        components.require(ActorRuntime.ACTOR_SCHEDULER)
                .register(GmQueryCommand.class, ActorHandler.sync((context, message) -> {
            GmQueryCommand command = (GmQueryCommand) message.payload();
            assertEquals(LaneKey.player(Long.toString(command.uid())), context.laneKey());
            state.rememberLane(context.laneKey());
            PlayerProfile profile = state.player(command.uid());
            state.gmResult("uid=" + profile.uid() + "|name=" + profile.name() + "|online=" + profile.online());
            appendLog(components, command.traceId(), "stage3 gm query completed", "gm-query");
            recordMetric(components, GM_QUERY_METRIC, "gm-query");
        }));
    }

    private Object registerGeneratedBos(
            final GeneratedRuntime generated,
            final GameRuntime components,
            final Stage3PrototypeState state) throws Exception {
        generated.login().registerMethod().invoke(generated.dispatcher(), proxy(generated.login().boClass(), request -> {
            String accountId = stringField(request, "accountId");
            long uid = 1001L;
            dispatchActor(components, LaneKey.session(accountId), stringField(request, "traceId"), new LoginCommand(
                    accountId,
                    uid,
                    stringField(request, "traceId")));
        }));
        generated.loadPlayer().registerMethod().invoke(
                generated.dispatcher(),
                proxy(generated.loadPlayer().boClass(), request -> dispatchActor(
                        components,
                        LaneKey.player(Long.toString(longField(request, "uid"))),
                        stringField(request, "traceId"),
                        new LoadPlayerCommand(longField(request, "uid"), stringField(request, "traceId")))));
        generated.enterScene().registerMethod().invoke(
                generated.dispatcher(),
                proxy(generated.enterScene().boClass(), request -> dispatchActor(
                        components,
                        LaneKey.scene(stringField(request, "sceneId")),
                        stringField(request, "traceId"),
                        new EnterSceneCommand(
                                longField(request, "uid"),
                                stringField(request, "sceneId"),
                                stringField(request, "traceId")))));
        generated.move().registerMethod().invoke(
                generated.dispatcher(),
                proxy(generated.move().boClass(), request -> dispatchActor(
                        components,
                        LaneKey.scene(stringField(request, "sceneId")),
                        stringField(request, "traceId"),
                        new MoveCommand(
                                longField(request, "uid"),
                                stringField(request, "sceneId"),
                                intField(request, "x"),
                                intField(request, "y"),
                                stringField(request, "traceId")))));
        generated.gmQueryPlayer().registerMethod().invoke(
                generated.dispatcher(),
                proxy(generated.gmQueryPlayer().boClass(), request -> dispatchActor(
                        components,
                        LaneKey.player(Long.toString(longField(request, "uid"))),
                        stringField(request, "traceId"),
                        new GmQueryCommand(
                                longField(request, "uid"),
                                stringField(request, "operatorId"),
                                stringField(request, "traceId")))));
        assertTrue(state.lanes().isEmpty());
        return generated.dispatcher();
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

    private void dispatchActor(
            final GameRuntime components,
            final LaneKey laneKey,
            final String traceId,
            final Object command) {
        components.require(ActorRuntime.ACTOR_SCHEDULER)
                .dispatch(new ActorMessage("stage3-" + command.getClass().getSimpleName(), laneKey, traceId, command))
                .toCompletableFuture()
                .join();
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
        components.require(LogRuntime.LOG_APPENDER).append(ZeroLogRecord.create(
                Instant.now(),
                LogLevel.INFO,
                LogType.BUSINESS,
                new LogSource("stage3-prototype", "local-test", "zero-server-starter"),
                new LogOperation(operation, LogResult.SUCCESS, null),
                traceId,
                message,
                Map.of()));
    }

    private void recordMetric(
            final GameRuntime components,
            final String metricName,
            final String operation) {
        components.require(MonitorRuntimeComponent.MONITOR_RUNTIME).registry().record(new MetricSample(
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
     * @param gmQueryPlayer GM 查询端点。
     */
    private record GeneratedRuntime(
            Object dispatcher,
            Method dispatchMethod,
            Endpoint login,
            Endpoint loadPlayer,
            Endpoint enterScene,
            Endpoint move,
            Endpoint gmQueryPlayer) {
    }

    /**
     * 阶段 3 原型状态。
     *
     * @author zn
     */
    private static final class Stage3PrototypeState {

        /**
         * 账号到玩家 ID 的映射。
         */
        private final Map<String, Long> sessions = new ConcurrentHashMap<>();

        /**
         * 玩家在线数据。
         */
        private final Map<Long, PlayerProfile> players = new ConcurrentHashMap<>();

        /**
         * 场景实体坐标。
         */
        private final Map<String, Map<Long, Position>> scenes = new ConcurrentHashMap<>();

        /**
         * 处理到的 lane 顺序。
         */
        private final List<LaneKey> lanes = new ArrayList<>();

        /**
         * GM 查询结果。
         */
        private final AtomicReference<String> gmResult = new AtomicReference<>("");

        /**
         * 记录登录态。
         *
         * @param accountId 账号 ID；不可为空。
         * @param uid 玩家 ID。
         */
        void login(final String accountId, final long uid) {
            sessions.put(Objects.requireNonNull(accountId, "accountId"), uid);
        }

        /**
         * 返回登录态玩家 ID。
         *
         * @param accountId 账号 ID；不可为空。
         * @return 玩家 ID；不存在时返回 -1。
         */
        long sessionUid(final String accountId) {
            return sessions.getOrDefault(accountId, -1L);
        }

        /**
         * 加载玩家在线数据。
         *
         * @param profile 玩家档案；不可为空。
         */
        void loadPlayer(final PlayerProfile profile) {
            players.put(profile.uid(), profile);
        }

        /**
         * 返回玩家档案。
         *
         * @param uid 玩家 ID。
         * @return 玩家档案；不可为空。
         */
        PlayerProfile player(final long uid) {
            return players.get(uid);
        }

        /**
         * 进入场景。
         *
         * @param sceneId 场景 ID；不可为空。
         * @param uid 玩家 ID。
         * @param position 坐标；不可为空。
         */
        void enterScene(final String sceneId, final long uid, final Position position) {
            scenes.computeIfAbsent(sceneId, ignored -> new ConcurrentHashMap<>()).put(uid, position);
        }

        /**
         * 移动场景实体。
         *
         * @param sceneId 场景 ID；不可为空。
         * @param uid 玩家 ID。
         * @param position 坐标；不可为空。
         */
        void move(final String sceneId, final long uid, final Position position) {
            scenes.computeIfAbsent(sceneId, ignored -> new ConcurrentHashMap<>()).put(uid, position);
        }

        /**
         * 返回场景坐标。
         *
         * @param sceneId 场景 ID；不可为空。
         * @param uid 玩家 ID。
         * @return 坐标；不可为空。
         */
        Position position(final String sceneId, final long uid) {
            return scenes.getOrDefault(sceneId, Map.of()).get(uid);
        }

        /**
         * 记录处理 lane。
         *
         * @param laneKey lane key；不可为空。
         */
        void rememberLane(final LaneKey laneKey) {
            lanes.add(Objects.requireNonNull(laneKey, "laneKey"));
        }

        /**
         * 返回处理 lane 快照。
         *
         * @return 不可变、有序、可能为空、非线程安全快照。
         */
        List<LaneKey> lanes() {
            return List.copyOf(lanes);
        }

        /**
         * 设置 GM 查询结果。
         *
         * @param value 查询结果；不可为空。
         */
        void gmResult(final String value) {
            gmResult.set(Objects.requireNonNull(value, "value"));
        }

        /**
         * 返回 GM 查询结果。
         *
         * @return 查询结果；不可为空。
         */
        String gmResult() {
            return gmResult.get();
        }
    }

    /**
     * 玩家档案。
     *
     * @param uid 玩家 ID。
     * @param name 玩家名。
     * @param online 是否在线。
     */
    private record PlayerProfile(long uid, String name, boolean online) {
    }

    /**
     * 场景坐标。
     *
     * @param x 横坐标。
     * @param y 纵坐标。
     */
    private record Position(int x, int y) {
    }

    /**
     * 登录命令。
     *
     * @param accountId 账号 ID。
     * @param uid 玩家 ID。
     * @param traceId 链路追踪 ID。
     */
    private record LoginCommand(String accountId, long uid, String traceId) {
    }

    /**
     * 玩家加载命令。
     *
     * @param uid 玩家 ID。
     * @param traceId 链路追踪 ID。
     */
    private record LoadPlayerCommand(long uid, String traceId) {
    }

    /**
     * 进入场景命令。
     *
     * @param uid 玩家 ID。
     * @param sceneId 场景 ID。
     * @param traceId 链路追踪 ID。
     */
    private record EnterSceneCommand(long uid, String sceneId, String traceId) {
    }

    /**
     * 移动命令。
     *
     * @param uid 玩家 ID。
     * @param sceneId 场景 ID。
     * @param x 横坐标。
     * @param y 纵坐标。
     * @param traceId 链路追踪 ID。
     */
    private record MoveCommand(long uid, String sceneId, int x, int y, String traceId) {
    }

    /**
     * GM 查询命令。
     *
     * @param uid 玩家 ID。
     * @param operatorId 操作员 ID。
     * @param traceId 链路追踪 ID。
     */
    private record GmQueryCommand(long uid, String operatorId, String traceId) {
    }
}
