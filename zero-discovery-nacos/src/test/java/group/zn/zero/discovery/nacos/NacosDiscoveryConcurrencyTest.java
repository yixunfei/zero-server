package group.zn.zero.discovery.nacos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.discovery.DiscoveryErrorCode;
import group.zn.zero.discovery.ServiceDiscoveryConstants;
import group.zn.zero.discovery.ServiceEvent;
import group.zn.zero.discovery.ServiceInstance;
import group.zn.zero.discovery.ServiceQuery;
import group.zn.zero.discovery.ServiceSubscription;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Nacos 生命周期事务和 SDK 回调边界的并发回归。 */
class NacosDiscoveryConcurrencyTest {

    private static final long WAIT_SECONDS = 5L;

    /** 远端注册未返回时，停止必须等待本地提交后再清理。 */
    @Test
    void stopWaitsForRegistrationCommit() throws Exception {
        CountDownLatch remoteRegisterEntered = new CountDownLatch(1);
        CountDownLatch releaseRemoteRegister = new CountDownLatch(1);
        List<String> calls = new CopyOnWriteArrayList<>();
        NamingService client = namingService((proxy, method, arguments) -> switch (method.getName()) {
            case "getServerStatus" -> "UP";
            case "registerInstance" -> {
                calls.add("register");
                remoteRegisterEntered.countDown();
                await(releaseRemoteRegister);
                yield null;
            }
            case "getAllInstances", "selectInstances" -> List.of();
            case "deregisterInstance" -> {
                calls.add("deregister");
                yield null;
            }
            case "shutDown" -> {
                calls.add("shutdown");
                yield null;
            }
            default -> defaultValue(method.getReturnType());
        });
        NacosDiscoveryAdapter adapter = new NacosDiscoveryAdapter(settings(), ignored -> client);
        CompletableFuture<Void> registered = new CompletableFuture<>();
        CompletableFuture<Void> stopped = new CompletableFuture<>();
        Thread registerThread = null;
        Thread stopThread = null;
        adapter.start();
        try {
            registerThread = start(() -> adapter.register(instance()), registered);
            assertTrue(remoteRegisterEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));
            stopThread = start(adapter::stop, stopped);
            Thread.sleep(50L);
            assertFalse(stopped.isDone());
            assertEquals(List.of("register"), calls);

            releaseRemoteRegister.countDown();
            registered.get(WAIT_SECONDS, TimeUnit.SECONDS);
            stopped.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals(List.of("register", "deregister", "shutdown"), calls);
            assertTrue(adapter.snapshot().isEmpty());
        } finally {
            releaseRemoteRegister.countDown();
            join(registerThread);
            join(stopThread);
            adapter.stop();
        }
    }

    /** SDK 同步回调不应持生命周期锁，且旧订阅句柄不得取消新客户端。 */
    @Test
    void callbackIsFilteredOutsideLockAndOldHandleCannotTouchRestartedClient() throws Exception {
        AtomicReference<EventListener> sdkListener = new AtomicReference<>();
        AtomicInteger createdClients = new AtomicInteger();
        AtomicInteger newClientUnsubscribes = new AtomicInteger();
        List<ServiceEvent> delivered = new CopyOnWriteArrayList<>();
        NamingEvent event = event();
        NamingService first = namingService((proxy, method, arguments) -> switch (method.getName()) {
            case "getServerStatus" -> "UP";
            case "getAllInstances", "selectInstances" -> event.getInstances();
            case "subscribe" -> {
                sdkListener.set((EventListener) arguments[3]);
                sdkListener.get().onEvent(event);
                yield null;
            }
            case "unsubscribe" -> throw new NacosException(
                    NacosException.SERVER_ERROR, "injected unsubscribe failure");
            case "shutDown" -> null;
            default -> defaultValue(method.getReturnType());
        });
        NamingService second = namingService((proxy, method, arguments) -> switch (method.getName()) {
            case "getServerStatus" -> "UP";
            case "getAllInstances", "selectInstances" -> List.of();
            case "unsubscribe" -> {
                newClientUnsubscribes.incrementAndGet();
                yield null;
            }
            default -> defaultValue(method.getReturnType());
        });
        NacosDiscoveryAdapter adapter = new NacosDiscoveryAdapter(
                settings(), ignored -> createdClients.getAndIncrement() == 0 ? first : second);
        ServiceQuery query = new ServiceQuery(
                "logic-service", ServiceDiscoveryConstants.DEFAULT_GROUP_NAME,
                List.of("blue"), true, true);
        adapter.start();
        ServiceSubscription subscription = adapter.subscribe(query, current ->
                recordCallback(adapter, query, current, delivered));
        assertEquals(1, delivered.size());
        assertEquals(List.of("healthy-blue"), delivered.getFirst().instances().stream()
                .map(ServiceInstance::instanceId).toList());
        assertFalse(delivered.getFirst().clusters().contains("red"));

        assertThrows(ZeroException.class, adapter::stop);
        adapter.start();
        subscription.close();
        sdkListener.get().onEvent(event);

        assertEquals(2, createdClients.get());
        assertEquals(0, newClientUnsubscribes.get());
        assertEquals(1, delivered.size());
        adapter.stop();
    }

    private static void recordCallback(
            final NacosDiscoveryAdapter adapter,
            final ServiceQuery query,
            final ServiceEvent event,
            final List<ServiceEvent> delivered) {
        assertFalse(Thread.holdsLock(adapter));
        CompletableFuture<Void> lookup = CompletableFuture.runAsync(() ->
                assertEquals(1, adapter.lookup(query).size()));
        lookup.join();
        delivered.add(event);
    }

    private static NacosDiscoverySettings settings() {
        return new NacosDiscoverySettings(
                "127.0.0.1:8848", NacosDiscoverySettings.DEFAULT_NAMESPACE,
                "", "", "", "", ServiceDiscoveryConstants.DEFAULT_GROUP_NAME,
                ServiceDiscoveryConstants.DEFAULT_CLUSTER_NAME, 1_000, false,
                NacosHealthUpdateMode.REREGISTER, Map.of());
    }

    private static ServiceInstance instance() {
        return new ServiceInstance("logic-service", "logic-1", "127.0.0.1", 6200, true, Map.of());
    }

    private static NamingEvent event() {
        return new NamingEvent(
                "logic-service", ServiceDiscoveryConstants.DEFAULT_GROUP_NAME, "blue,red",
                List.of(sdkInstance("healthy-blue", "blue", true),
                        sdkInstance("unhealthy-blue", "blue", false),
                        sdkInstance("healthy-red", "red", true)));
    }

    private static Instance sdkInstance(final String id, final String cluster, final boolean healthy) {
        Instance instance = new Instance();
        instance.setInstanceId(id);
        instance.setIp("127.0.0.1");
        instance.setPort(6200);
        instance.setClusterName(cluster);
        instance.setHealthy(healthy);
        instance.setEnabled(true);
        instance.setEphemeral(true);
        instance.setWeight(1.0D);
        instance.setMetadata(Map.of());
        return instance;
    }

    private static NamingService namingService(final InvocationHandler delegate) {
        InvocationHandler handler = (proxy, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> "TestNamingService";
                    default -> null;
                };
            }
            return delegate.invoke(proxy, method, arguments);
        };
        return (NamingService) Proxy.newProxyInstance(
                NamingService.class.getClassLoader(), new Class<?>[] {NamingService.class}, handler);
    }

    private static Thread start(final Runnable action, final CompletableFuture<Void> completion) {
        return Thread.ofPlatform().daemon().start(() -> {
            try {
                action.run();
                completion.complete(null);
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
        });
    }

    private static void await(final CountDownLatch latch) {
        try {
            assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static void join(final Thread thread) throws InterruptedException {
        if (thread != null) {
            thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            assertFalse(thread.isAlive());
        }
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0D;
        }
        if (type == float.class) {
            return 0.0F;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        return 0;
    }
}
