package group.zn.zero.actor.scheduler;

import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 写时复制的有序处理器注册表。写操作独占注册锁，调度读取不可变快照且不加锁。
 * 精确类型优先，其次按注册顺序匹配父类/接口；注册频率应远低于消息投递频率。
 *
 * @author zn
 */
final class OrderedActorHandlers {
    /** 当前有序快照；发布后不再修改。 */
    private volatile Snapshot snapshot = new Snapshot(Map.of());

    /**
     * 注册处理器；线程安全，仅变更注册表。
     * @param type 消息体类型；不可为空。
     * @param handler 处理器；不可为空。
     * @return 幂等注销句柄；不可为空，线程安全。
     * @throws NullPointerException 参数为空。
     * @throws ZeroException 类型已注册。
     */
    synchronized ActorSubscription register(final Class<?> type, final ActorHandler handler) {
        Objects.requireNonNull(type, "payloadType");
        Objects.requireNonNull(handler, "handler");
        if (snapshot.entries.containsKey(type)) {
            throw invalid("Actor handler already registered: " + type.getName());
        }
        Registration registration = new Registration(handler);
        Map<Class<?>, Registration> next = new LinkedHashMap<>(snapshot.entries);
        next.put(type, registration);
        snapshot = new Snapshot(Collections.unmodifiableMap(next));
        return () -> unregister(type, registration);
    }

    /**
     * 从同一快照查找处理器；线程安全，不修改状态。
     * @param type 消息体类型；不可为空。
     * @return 匹配处理器；不可为空；其线程安全性由实现保证。
     * @throws ZeroException 未匹配到注册类型。
     */
    ActorHandler find(final Class<?> type) {
        Snapshot current = snapshot;
        Registration exact = current.entries.get(type);
        if (exact != null) return exact.handler();
        Registration resolved = current.resolved.get(type).get();
        java.lang.ref.Reference.reachabilityFence(current);
        if (resolved != null) return resolved.handler();
        throw invalid("Actor handler not found: " + type.getName());
    }

    private synchronized void unregister(final Class<?> type, final Registration registration) {
        // 比较本次注册身份，旧句柄不能删除使用相同 handler 对象的新注册。
        if (snapshot.entries.get(type) == registration) {
            Map<Class<?>, Registration> next = new LinkedHashMap<>(snapshot.entries);
            next.remove(type);
            snapshot = new Snapshot(Collections.unmodifiableMap(next));
        }
    }

    private static ZeroException invalid(final String message) {
        return ZeroException.of(SystemErrorCode.INVALID_ARGUMENT, message, null);
    }

    /** 单次注册身份；handler 不可为空。 @author zn */
    private record Registration(ActorHandler handler) { }

    /** 注册快照独占解析缓存；ClassValue 不强持有派生消息的 ClassLoader。 @author zn */
    private static final class Snapshot {
        /** 不可变、按注册顺序遍历的目录。 */
        private final Map<Class<?>, Registration> entries;
        /**
         * 缓存包括未匹配结果；注册/注销发布新快照使其失效。
         * 弱值避免 handler 捕获 scheduler 时，经永久消息 Class 的缓存反向钉住整个调度器。
         * 活跃快照的 entries 始终强持有已注册值，因此有效匹配不会因 GC 消失。
         */
        private final ClassValue<java.lang.ref.WeakReference<Registration>> resolved = new ClassValue<>() {
            @Override protected java.lang.ref.WeakReference<Registration> computeValue(final Class<?> type) {
                for (Map.Entry<Class<?>, Registration> entry : entries.entrySet()) {
                    if (entry.getKey().isAssignableFrom(type)) return new java.lang.ref.WeakReference<>(entry.getValue());
                }
                return new java.lang.ref.WeakReference<>(null);
            }
        };
        private Snapshot(final Map<Class<?>, Registration> entries) { this.entries = entries; }
    }
}
