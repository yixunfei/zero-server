package group.zn.zero.benchmark.performance;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.scheduler.ExecutorActorScheduler;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** 显式 GC 的独立生命周期探针；不把 GC 时间混入性能测量，也不加入易受 GC 配置影响的单测。 @author zn */
public final class ClassValueLifetimeProbe {
    private ClassValueLifetimeProbe() { }
    /**
     * 检查永久消息 Class 不钉住销毁的 scheduler、活跃 scheduler 不钉住派生消息 ClassLoader。
     * @param args 无参数。
     * @throws Exception GC 等待被中断。
     */
    public static void main(final String[] args) throws Exception {
        List<WeakReference<?>> schedulers = new ArrayList<>();
        for (int i = 0; i < 1000; i++) schedulers.add(disposableScheduler());
        var live = new ExecutorActorScheduler(Runnable::run);
        live.register(Runnable.class, ActorHandler.sync((context, message) -> { }));
        List<WeakReference<?>> loaders = new ArrayList<>();
        for (int i = 0; i < 100; i++) loaders.add(disposablePayload(live));
        for (int attempt = 0; attempt < 30 && (alive(schedulers) > 0 || alive(loaders) > 0); attempt++) {
            System.gc();
            Thread.sleep(100);
        }
        System.out.printf("{\"schedulerRetained\":%d,\"schedulerTotal\":1000,\"loaderRetained\":%d,\"loaderTotal\":100}%n",
                alive(schedulers), alive(loaders));
        java.lang.ref.Reference.reachabilityFence(live);
        if (alive(schedulers) != 0 || alive(loaders) != 0) throw new IllegalStateException("lifecycle references retained");
    }
    private static WeakReference<?> disposableScheduler() {
        var scheduler = new ExecutorActorScheduler(Runnable::run);
        scheduler.register(CharSequence.class, ActorHandler.sync((context, message) -> System.identityHashCode(scheduler)));
        scheduler.dispatch(new ActorMessage(LaneKey.custom("probe"), "payload")).toCompletableFuture().join();
        return new WeakReference<>(scheduler);
    }
    private static WeakReference<?> disposablePayload(final ExecutorActorScheduler scheduler) {
        ClassLoader loader = new ClassLoader(null) { };
        Object payload = Proxy.newProxyInstance(loader, new Class<?>[] {Runnable.class}, (proxy, method, arguments) -> null);
        scheduler.dispatch(new ActorMessage(LaneKey.custom("probe"), payload)).toCompletableFuture().join();
        return new WeakReference<>(loader);
    }
    private static long alive(final List<WeakReference<?>> values) { return values.stream().filter(value -> value.get() != null).count(); }
}
