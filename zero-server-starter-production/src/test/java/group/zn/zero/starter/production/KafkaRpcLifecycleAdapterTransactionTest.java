package group.zn.zero.starter.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.lifecycle.LifecycleState;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import group.zn.zero.rpc.kafka.KafkaRpcAdapter;
import group.zn.zero.rpc.kafka.KafkaRpcSettings;
import group.zn.zero.rpc.observer.RpcTransportObserver;
import group.zn.zero.rpc.spi.RpcHandler;
import group.zn.zero.rpc.spi.RpcRoute;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * {@link KafkaRpcLifecycleAdapter} handler 状态事务与生命周期线性化测试。
 *
 * @author zn
 */
class KafkaRpcLifecycleAdapterTransactionTest {

    /** 用于反证动态注册、注销与恢复原始失败不会进入公开异常图的敏感哨兵。 */
    private static final String SECRET = "PAF1-KAFKA-HANDLER-TRANSACTION-SECRET";

    /** 并发测试等待上限秒数。 */
    private static final long WAIT_SECONDS = 5L;

    /**
     * 验证 start 回放/发布、动态 register 与 close 共享同一冷路径线性化边界，边界注册不丢失、不重复，
     * close 不会越过尚未提交的动态注册。
     *
     * @throws InterruptedException 当前测试线程等待故障注入线程时被中断。
     */
    @Test
    void startRegisterAndCloseShouldLinearizeWithoutLostOrDuplicateHandler() throws InterruptedException {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch replayEntered = new CountDownLatch(1);
        CountDownLatch releaseReplay = new CountDownLatch(1);
        CountDownLatch dynamicEntered = new CountDownLatch(1);
        CountDownLatch releaseDynamic = new CountDownLatch(1);
        ScriptedResource resource = new ScriptedResource("resource", events);
        resource.gateRegistration(1, replayEntered, releaseReplay);
        resource.gateRegistration(2, dynamicEntered, releaseDynamic);
        KafkaRpcLifecycleAdapter lifecycle = lifecycleWith(new ArrayDeque<>(List.of(resource)));
        lifecycle.register("existing-service", "existing-method", testHandler());

        AtomicReference<Throwable> startFailure = new AtomicReference<>();
        AtomicReference<Throwable> registerFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread startThread = null;
        Thread registerThread = null;
        Thread closeThread = null;
        try {
            startThread = runAsync("paf1-kafka-start", lifecycle::start, startFailure);
            assertTrue(replayEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));
            registerThread = runAsync(
                    "paf1-kafka-register",
                    () -> lifecycle.register("dynamic-service", "dynamic-method", testHandler()),
                    registerFailure);
            releaseReplay.countDown();
            assertTrue(dynamicEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));
            join(startThread);
            closeThread = runAsync("paf1-kafka-close", lifecycle::close, closeFailure);
            releaseDynamic.countDown();
            join(registerThread);
            join(closeThread);

            assertNull(startFailure.get());
            assertNull(registerFailure.get());
            assertNull(closeFailure.get());
            assertEquals(List.of(
                    "resource:register:existing-service/existing-method#1",
                    "resource:register:dynamic-service/dynamic-method#2",
                    "resource:close#1"), List.copyOf(events));
            assertEquals(2, resource.registrationCalls());
            assertEquals(1, resource.closeCalls());
            assertFalse(lifecycle.resourcePublished());
            assertEquals(LifecycleState.STOPPED, lifecycle.state());
        } finally {
            releaseReplay.countDown();
            releaseDynamic.countDown();
            joinQuietly(startThread);
            joinQuietly(registerThread);
            joinQuietly(closeThread);
            lifecycle.close();
        }
    }

    /**
     * 验证动态 replacement 与 unregister 在底层失败时恢复上一 handler，wrapper replay map 只在成功后提交；
     * 恢复失败只作为安全 ROLLBACK suppressed，重启仍回放旧 handler。
     */
    @Test
    void registrationFailuresShouldRetainPreviousReplayStateAndSanitizeRollback() {
        List<String> events = new ArrayList<>();
        ScriptedResource first = new ScriptedResource("first", events);
        first.failRegistration(2, new AssertionError(SECRET + "-replace"));
        first.failRegistration(3, new IllegalStateException(SECRET + "-restore"));
        first.failNextUnregister(new AssertionError(SECRET + "-unregister"));
        ScriptedResource second = new ScriptedResource("second", events);
        KafkaRpcLifecycleAdapter lifecycle = lifecycleWith(new ArrayDeque<>(List.of(first, second)));
        RpcHandler previousHandler = testHandler();
        RpcHandler replacementHandler = testHandler();
        lifecycle.register("service", "method", previousHandler);

        try {
            lifecycle.start();
            ProductionAdapterException registerFailure = assertThrows(
                    ProductionAdapterException.class,
                    () -> lifecycle.register("service", "method", replacementHandler));
            assertRegistrationFailure(registerFailure);
            assertEquals(1, registerFailure.getSuppressed().length);
            assertSafeRollback(registerFailure.getSuppressed()[0]);
            assertSame(previousHandler, first.handler("service", "method"));

            ProductionAdapterException unregisterFailure = assertThrows(
                    ProductionAdapterException.class,
                    () -> lifecycle.unregister("service", "method"));
            assertRegistrationFailure(unregisterFailure);
            assertEquals(0, unregisterFailure.getSuppressed().length);
            assertSame(previousHandler, first.handler("service", "method"));

            lifecycle.stop();
            lifecycle.start();
            assertSame(previousHandler, second.handler("service", "method"));
            assertEquals(1, second.registrationCalls());
            assertTrue(lifecycle.resourcePublished());
            assertFalse(stackTrace(registerFailure).contains(SECRET), stackTrace(registerFailure));
            assertFalse(stackTrace(unregisterFailure).contains(SECRET), stackTrace(unregisterFailure));
        } finally {
            lifecycle.close();
        }
    }

    /**
     * 创建使用队列资源工厂的 lifecycle adapter。
     *
     * @param resources 依次用于每次 start 的资源；不可为空，调用后由当前测试线程消费。
     * @return 未启动 lifecycle adapter；不可为空，调用方负责关闭。
     */
    private KafkaRpcLifecycleAdapter lifecycleWith(final Deque<KafkaRpcAdapterResource> resources) {
        return new KafkaRpcLifecycleAdapter(
                KafkaRpcSettings.defaults("127.0.0.1:9092"),
                RpcTransportObserver.noop(),
                (settings, observer) -> resources.removeFirst());
    }

    /**
     * 创建测试 handler；本测试只比较身份，不执行 handler。
     *
     * @return 独立 RPC handler；不可为空，无共享可变业务状态。
     */
    private RpcHandler testHandler() {
        return request -> CompletableFuture.failedFuture(
                new AssertionError("transaction test handler must not execute"));
    }

    /**
     * 在线程中执行一个 lifecycle 动作并捕获全部失败。
     *
     * @param name 测试线程名；不可为空。
     * @param action lifecycle 动作；不可为空。
     * @param failure 捕获失败的容器；不可为空，仅写入一次。
     * @return 已启动虚拟线程；不可为空，调用方必须 join。
     */
    private Thread runAsync(
            final String name,
            final Runnable action,
            final AtomicReference<Throwable> failure) {
        return Thread.ofVirtual().name(name).start(() -> {
            try {
                action.run();
            } catch (Throwable currentFailure) {
                failure.set(currentFailure);
            }
        });
    }

    /**
     * 有上限地等待线程结束，并断言没有遗留并发任务。
     *
     * @param thread 待等待线程；不可为空。
     * @throws InterruptedException 当前测试线程等待时被中断。
     */
    private void join(final Thread thread) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertFalse(thread.isAlive(), thread.getName());
    }

    /**
     * finally 中释放并等待可空测试线程，避免失败断言遗留任务。
     *
     * @param thread 待等待线程；可为空。
     */
    private void joinQuietly(final Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            join(thread);
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 断言动态 handler 修改失败使用固定安全注册归因。
     *
     * @param failure 待断言失败；不可为空。
     */
    private void assertRegistrationFailure(final ProductionAdapterException failure) {
        assertEquals(ZeroProductionRuntimeBuilder.ADAPTER_KAFKA_RPC, failure.adapterName());
        assertEquals(ProductionAdapterFailurePhase.REGISTRATION, failure.failurePhase());
        assertSame(ProductionAdapterErrorCode.REGISTRATION_FAILED, failure.errorCode());
        assertEquals(ProductionAdapterErrorCode.REGISTRATION_FAILED.message(), failure.message());
        assertNull(failure.getCause());
    }

    /**
     * 断言动态 handler 事务恢复失败被安全重分类。
     *
     * @param failure 待断言 suppressed；不可为空。
     */
    private void assertSafeRollback(final Throwable failure) {
        ProductionAdapterException rollback = assertInstanceOf(
                ProductionAdapterException.class,
                failure);
        assertEquals(ZeroProductionRuntimeBuilder.ADAPTER_KAFKA_RPC, rollback.adapterName());
        assertEquals(ProductionAdapterFailurePhase.ROLLBACK, rollback.failurePhase());
        assertSame(ProductionAdapterErrorCode.ROLLBACK_FAILED, rollback.errorCode());
        assertNull(rollback.getCause());
    }

    /**
     * 把完整异常图打印为文本，供敏感哨兵反证。
     *
     * @param failure 待打印失败；不可为空。
     * @return 完整堆栈文本；不可为空，调用方可变，方法不修改异常图。
     */
    private String stackTrace(final Throwable failure) {
        StringWriter writer = new StringWriter();
        try (PrintWriter printer = new PrintWriter(writer)) {
            failure.printStackTrace(printer);
        }
        return writer.toString();
    }

    /**
     * 可注入注册/注销失败与阻塞点的发布前 Kafka 资源。
     *
     * @author zn
     */
    private static final class ScriptedResource implements KafkaRpcAdapterResource {

        /** 资源稳定名称。 */
        private final String name;

        /** 有序动作记录；并发测试中使用同步 List。 */
        private final List<String> events;

        /** 当前资源 handler 快照；调用由 lifecycle monitor 串行化。 */
        private final Map<RpcRoute, RpcHandler> handlers = new LinkedHashMap<>();

        /** 指定注册调用的阻塞门。 */
        private final Map<Integer, RegistrationGate> registrationGates = new HashMap<>();

        /** 指定注册调用完成状态变更后抛出的失败。 */
        private final Map<Integer, Throwable> registrationFailures = new HashMap<>();

        /** 下一次注销完成状态变更后抛出的失败。 */
        private Throwable nextUnregisterFailure;

        /** 注册调用次数。 */
        private int registrationCalls;

        /** 关闭调用次数。 */
        private int closeCalls;

        /**
         * 创建脚本化 Kafka 资源。
         *
         * @param name 资源名称；不可为空。
         * @param events 有序动作记录；不可为空，线程安全性由调用方选择。
         */
        private ScriptedResource(final String name, final List<String> events) {
            this.name = name;
            this.events = events;
        }

        /**
         * 为指定注册调用设置确定性阻塞门。
         *
         * @param call 注册调用序号，从 1 开始。
         * @param entered 进入调用后的通知；不可为空。
         * @param release 允许调用继续的通知；不可为空。
         */
        private void gateRegistration(
                final int call,
                final CountDownLatch entered,
                final CountDownLatch release) {
            registrationGates.put(call, new RegistrationGate(entered, release));
        }

        /**
         * 为指定注册调用设置状态变更后的失败。
         *
         * @param call 注册调用序号，从 1 开始。
         * @param failure 非受检失败；不可为空。
         */
        private void failRegistration(final int call, final Throwable failure) {
            registrationFailures.put(call, failure);
        }

        /**
         * 设置下一次注销在移除 handler 后抛出的失败。
         *
         * @param failure 非受检失败；不可为空。
         */
        private void failNextUnregister(final Throwable failure) {
            nextUnregisterFailure = failure;
        }

        /**
         * 返回注册调用次数。
         *
         * @return 非负次数，仅在并发线程结束后读取。
         */
        private int registrationCalls() {
            return registrationCalls;
        }

        /**
         * 返回关闭调用次数。
         *
         * @return 非负次数，仅在并发线程结束后读取。
         */
        private int closeCalls() {
            return closeCalls;
        }

        /**
         * 返回指定路由当前 handler。
         *
         * @param serviceName 服务名；不可为空。
         * @param methodName 方法名；不可为空。
         * @return 当前 handler；可能为空，仅在 lifecycle 操作完成后读取。
         */
        private RpcHandler handler(final String serviceName, final String methodName) {
            return handlers.get(new RpcRoute(serviceName, methodName));
        }

        /**
         * 记录并应用 handler，随后按脚本阻塞或抛出失败。
         *
         * @param serviceName 服务名；不可为空。
         * @param methodName 方法名；不可为空。
         * @param topic request topic；不可为空，可为空串。
         * @param group consumer group；不可为空，可为空串。
         * @param handler RPC handler；不可为空。
         * @throws RuntimeException 脚本失败为运行时异常时抛出。
         * @throws Error 脚本失败为严重错误时抛出。
         */
        @Override
        public void register(
                final String serviceName,
                final String methodName,
                final String topic,
                final String group,
                final RpcHandler handler) {
            registrationCalls++;
            events.add(name + ":register:" + serviceName + "/" + methodName + "#" + registrationCalls);
            handlers.put(new RpcRoute(serviceName, methodName), handler);
            RegistrationGate gate = registrationGates.get(registrationCalls);
            if (gate != null) {
                gate.await();
            }
            throwUnchecked(registrationFailures.get(registrationCalls));
        }

        /**
         * 移除 handler，随后按脚本抛出一次注销失败。
         *
         * @param serviceName 服务名；不可为空。
         * @param methodName 方法名；不可为空。
         * @throws RuntimeException 脚本失败为运行时异常时抛出。
         * @throws Error 脚本失败为严重错误时抛出。
         */
        @Override
        public void unregister(final String serviceName, final String methodName) {
            events.add(name + ":unregister:" + serviceName + "/" + methodName);
            handlers.remove(new RpcRoute(serviceName, methodName));
            Throwable failure = nextUnregisterFailure;
            nextUnregisterFailure = null;
            throwUnchecked(failure);
        }

        /**
         * 返回永远失败的占位 request 阶段；事务测试不会执行传输。
         *
         * @param request RPC 请求；不可为空。
         * @return 已失败阶段；不可为空，线程安全。
         */
        @Override
        public CompletionStage<RpcResponse> request(final RpcRequest request) {
            return CompletableFuture.failedFuture(
                    new AssertionError("scripted resource must not send request"));
        }

        /**
         * 返回永远失败的占位 oneway 阶段；事务测试不会执行传输。
         *
         * @param request RPC 请求；不可为空。
         * @return 已失败阶段；不可为空，线程安全。
         */
        @Override
        public CompletionStage<Void> oneway(final RpcRequest request) {
            return CompletableFuture.failedFuture(
                    new AssertionError("scripted resource must not send oneway"));
        }

        /**
         * 故障注入资源不公开真实 Kafka Adapter。
         *
         * @return 空容器；不可为空，线程安全。
         */
        @Override
        public Optional<KafkaRpcAdapter> exposedAdapter() {
            return Optional.empty();
        }

        /** 记录一次线性化关闭，不修改外部资源。 */
        @Override
        public void close() {
            closeCalls++;
            events.add(name + ":close#" + closeCalls);
        }

        /**
         * 抛出预设非受检失败；为空时直接返回。
         *
         * @param failure 预设失败；可为空。
         * @throws RuntimeException 失败为运行时异常时抛出。
         * @throws Error 失败为严重错误时抛出。
         */
        private void throwUnchecked(final Throwable failure) {
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    /**
     * 单次注册阻塞门。
     *
     * @param entered 进入注册后的通知。
     * @param release 允许注册继续的通知。
     * @author zn
     */
    private record RegistrationGate(CountDownLatch entered, CountDownLatch release) {

        /**
         * 通知进入并有上限地等待释放。
         *
         * @throws AssertionError 等待超时或被中断时抛出。
         */
        private void await() {
            entered.countDown();
            try {
                if (!release.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                    throw new AssertionError("registration gate timed out");
                }
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new AssertionError("registration gate interrupted", interruption);
            }
        }
    }
}
