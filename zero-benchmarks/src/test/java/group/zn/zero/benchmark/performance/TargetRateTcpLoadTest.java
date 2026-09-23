package group.zn.zero.benchmark.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;

/** 在最后响应移除后暂停统计发布，验证最终汇总不会丢失完成数。 @author zn */
class TargetRateTcpLoadTest {
    @Test void finalReportWaitsForResponseAccountingAfterPendingRemoval() throws Exception {
        var constructor = TargetRateTcpLoad.class.getDeclaredConstructor(boolean.class);
        constructor.setAccessible(true);
        var load = constructor.newInstance(false);
        var counter = new PausedCounter();
        var completed = TargetRateTcpLoad.class.getDeclaredField("completed");
        completed.setAccessible(true);
        completed.set(load, counter);
        var run = TargetRateTcpLoad.class.getDeclaredMethod("run",
                int.class, int.class, int.class, int.class, int.class, boolean.class);
        run.setAccessible(true);
        try (var server = new ServerSocket(0); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setSoTimeout(3000);
            var echo = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    byte[] frame = input.readNBytes(input.readInt());
                    output.writeInt(frame.length);
                    output.write(frame);
                    output.flush();
                }
                return null;
            });
            var execution = executor.submit(() -> run.invoke(load, server.getLocalPort(), 1, 1, 1, 16, false));
            try {
                assertTrue(counter.entered.await(3, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> execution.get(200, TimeUnit.MILLISECONDS));
            } finally { counter.release.countDown(); }
            execution.get(3, TimeUnit.SECONDS);
            echo.get(3, TimeUnit.SECONDS);
            assertEquals(1, counter.sum());
        } finally { counter.release.countDown(); }
    }

    /** 只暂停计数发布，不改变真实 socket、payload 和在途表路径。 @author zn */
    private static final class PausedCounter extends LongAdder {
        /** 序列化标识；该测试对象不序列化。 */
        private static final long serialVersionUID = 1L;
        /** 完成回调已进入计数提交。 */
        private final CountDownLatch entered = new CountDownLatch(1);
        /** 测试允许计数发布。 */
        private final CountDownLatch release = new CountDownLatch(1);

        /** 确定性扩大在途移除到计数发布之间的窗口；线程安全。 */
        @Override public void increment() {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("counter release timed out");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("counter interrupted", failure);
            }
            super.increment();
        }
    }
}
