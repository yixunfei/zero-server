package group.zn.zero.benchmark.performance;

import com.sun.management.ThreadMXBean;
import group.zn.zero.room.LocalRoomService;
import group.zn.zero.room.RoomId;
import java.lang.management.ManagementFactory;
import java.util.Locale;

/** 满历史窗口的幂等房间命令分配探针；仅提供本地场景证据，不代表生产容量。 @author zn */
public final class RoomCommandAllocationProbe {
    /** 每轮调用数。 */
    private static final int ITERATIONS = 20_000;

    private RoomCommandAllocationProbe() { }

    /**
     * 输出五轮当前线程的分配和耗时；只使用内存房间及直接 Actor。
     * @param args 未使用；不访问外部服务，只修改本次创建的房间。
     * @throws IllegalStateException 当前 JVM 不支持线程分配计数时抛出。
     */
    public static void main(final String[] args) {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("allocation counter unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        LocalRoomService service = new LocalRoomService();
        RoomId room = new RoomId("allocation-probe");
        service.create(room, 1, 0);
        for (int index = 0; index < 600; index++) {
            service.join(room, "warm-history").toCompletableFuture().join();
            service.leave(room, "warm-history").toCompletableFuture().join();
        }
        service.join(room, "member").toCompletableFuture().join();
        invoke(service, room);
        long thread = Thread.currentThread().threadId();
        System.out.println("sample,history,bytes_per_operation,nanos_per_operation");
        for (int sample = 1; sample <= 5; sample++) {
            long allocated = bean.getThreadAllocatedBytes(thread);
            long started = System.nanoTime();
            invoke(service, room);
            long elapsed = System.nanoTime() - started;
            long bytes = bean.getThreadAllocatedBytes(thread) - allocated;
            System.out.printf(Locale.ROOT, "%d,%d,%.2f,%.2f%n", sample, service.events(room).size(),
                    (double) bytes / ITERATIONS, (double) elapsed / ITERATIONS);
        }
    }

    private static void invoke(final LocalRoomService service, final RoomId room) {
        for (int index = 0; index < ITERATIONS; index++) {
            service.join(room, "member").toCompletableFuture().join();
        }
    }
}
