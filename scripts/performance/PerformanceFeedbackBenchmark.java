import com.sun.management.ThreadMXBean;
import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.scheduler.ExecutorActorScheduler;
import group.zn.zero.aoi.AoiEntity;
import group.zn.zero.aoi.InMemoryAoiIndex;
import group.zn.zero.aoi.Position;
import group.zn.zero.protocol.ProtocolDefinition;
import group.zn.zero.protocol.ProtocolDirection;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.GeneratedProtocolCodec;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * 本地性能反馈基准；无外部依赖，不替代 JMH 或生产容量测试。
 * 使用相同源码和参数分别运行原始/优化 classes。输出逐轮结果，不设置时间阈值。
 *
 * @author zn
 */
public final class PerformanceFeedbackBenchmark {
    /** 消费输出引用，避免物化字节数组被优化掉。 */
    private static volatile Object sink;
    /** 测量当前线程分配，不将不可用指标伪装成零。 */
    private static final ThreadMXBean ALLOCATION = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    /** 预热轮数。 */
    private static final int WARMUP = 3;
    /** 测量轮数。 */
    private static final int MEASURE = 5;

    private PerformanceFeedbackBenchmark() { }

    /**
     * 执行 Actor、AOI、编码基准；命令行可选 actor/aoi/codec/all。
     * @param args 可选 workload；不可为空。
     * @throws Exception 工作线程失败或超过等待期限时抛出，禁止静默计入吞吐。
     */
    public static void main(final String[] args) throws Exception {
        System.out.printf("java=%s os=%s processors=%d warmup=%d measure=%d flags=%s%n",
                System.getProperty("java.version"), System.getProperty("os.name"),
                Runtime.getRuntime().availableProcessors(), WARMUP, MEASURE,
                ManagementFactory.getRuntimeMXBean().getInputArguments());
        String mode = args.length == 0 ? "all" : args[0];
        if ("all".equals(mode) || "actor".equals(mode)) {
            actor(1);
            actor(16_384);
        }
        if ("all".equals(mode) || "aoi".equals(mode)) {
            aoi(100, false);
            aoi(10_000, false);
            aoi(10_000, true);
        }
        if ("all".equals(mode) || "codec".equals(mode)) {
            for (int size : new int[] {64, 1024, 32_768, 131_072}) {
                codec(size);
            }
        }
    }

    private static void actor(final int laneCount) throws Exception {
        int producers = Integer.getInteger("bench.producers", 8);
        int messagesPerProducer = 20_000;
        LaneKey[] lanes = new LaneKey[laneCount];
        for (int i = 0; i < laneCount; i++) lanes[i] = LaneKey.player(Integer.toString(i));
        try (var workers = Executors.newFixedThreadPool(4); var callers = Executors.newFixedThreadPool(producers)) {
            var scheduler = new ExecutorActorScheduler(workers);
            long[] processed = new long[laneCount];
            scheduler.register(Integer.class, (context, message) -> {
                processed[(Integer) message.payload()]++;
                return CompletableFuture.completedFuture(null);
            });
            for (int round = -WARMUP; round < MEASURE; round++) {
                Arrays.fill(processed, 0L);
                var start = new CountDownLatch(1);
                List<java.util.concurrent.Future<?>> submitted = new ArrayList<>();
                for (int producer = 0; producer < producers; producer++) {
                    final int offset = producer * messagesPerProducer;
                    submitted.add(callers.submit(() -> {
                        if (!start.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                        CompletableFuture<?>[] pending = new CompletableFuture<?>[messagesPerProducer];
                        for (int i = 0; i < messagesPerProducer; i++) {
                            int lane = (offset + i) % laneCount;
                            pending[i] = scheduler.dispatch(new ActorMessage("bench", lanes[lane], "trace", lane))
                                    .toCompletableFuture();
                        }
                        CompletableFuture.allOf(pending).get(30, TimeUnit.SECONDS);
                        return null;
                    }));
                }
                long begin = System.nanoTime();
                start.countDown();
                for (var future : submitted) future.get(40, TimeUnit.SECONDS);
                long elapsed = System.nanoTime() - begin;
                long count = Arrays.stream(processed).sum();
                if (count != (long) producers * messagesPerProducer) throw new AssertionError("message loss");
                if (round >= 0) report("actor-producers-" + producers + "-lanes-" + laneCount,
                        round, count, elapsed, -1, count);
            }
        }
    }

    private static void aoi(final int entities, final boolean dense) {
        InMemoryAoiIndex index = new InMemoryAoiIndex();
        for (int i = 0; i < entities; i++) {
            Position position = dense ? new Position(0, 0) : new Position((i % 100) * 128, (i / 100) * 128);
            index.add(new AoiEntity(Integer.toString(i), position, 0, null));
        }
        int iterations = dense ? 500 : 5000;
        for (int round = -WARMUP; round < MEASURE; round++) {
            long checksum = 0;
            long allocated = allocated();
            long begin = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                int selected = i % entities;
                Position center = dense ? new Position(0, 0)
                        : new Position((selected % 100) * 128, (selected / 100) * 128);
                var visible = index.visible(center, 32);
                checksum += visible.size();
                sink = visible;
            }
            long elapsed = System.nanoTime() - begin;
            long bytes = allocationDelta(allocated);
            long expected = (long) iterations * (dense ? entities : 1);
            if (checksum != expected) throw new AssertionError("visibility mismatch");
            if (round >= 0) report("aoi-" + entities + (dense ? "-dense" : "-sparse"),
                    round, iterations, elapsed, bytes, checksum);
        }
    }

    private static void codec(final int size) {
        byte[] payload = new byte[size];
        Arrays.fill(payload, (byte) 37);
        var definition = new ProtocolDefinition(1, "benchmark", ProtocolDirection.values()[0], 1);
        var generated = new GeneratedProtocolCodec<>(new BytesCodec());
        var frameCodec = new ZeroBinaryFrameCodec();
        var frame = new ProtocolFrame(1, 1, 0, null, payload);
        if (!Arrays.equals(payload, generated.decode(definition, generated.encode(definition, payload), byte[].class))) {
            throw new AssertionError("generated round trip mismatch");
        }
        if (!Arrays.equals(payload, frameCodec.decode(frameCodec.encode(frame)).payload())) {
            throw new AssertionError("frame round trip mismatch");
        }
        int iterations = Math.max(2000, 32_000_000 / size);
        measureCodec("generated-" + size, iterations, ignored -> generated.encode(definition, payload));
        measureCodec("frame-" + size, iterations, ignored -> frameCodec.encode(frame));
    }

    private static void measureCodec(final String name, final int iterations, final IntFunction<byte[]> encode) {
        for (int round = -WARMUP; round < MEASURE; round++) {
            long checksum = 0;
            long allocated = allocated();
            long begin = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                byte[] bytes = encode.apply(i);
                sink = bytes;
                checksum += bytes.length + bytes[bytes.length - 1];
            }
            long elapsed = System.nanoTime() - begin;
            long bytes = allocationDelta(allocated);
            if (round >= 0) report(name, round, iterations, elapsed, bytes, checksum);
        }
    }

    private static long allocated() {
        return ALLOCATION.isThreadAllocatedMemorySupported() && ALLOCATION.isThreadAllocatedMemoryEnabled()
                ? ALLOCATION.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1;
    }

    private static long allocationDelta(final long before) {
        return before < 0 ? -1 : allocated() - before;
    }

    private static void report(final String name, final int round, final long count,
            final long elapsed, final long allocation, final long checksum) {
        System.out.printf(java.util.Locale.ROOT,
                "%s round=%d ops=%d ns/op=%.2f ops/s=%.2f B/op=%.2f checksum=%d%n",
                name, round, count, (double) elapsed / count, count * 1_000_000_000.0 / elapsed,
                allocation < 0 ? -1.0 : (double) allocation / count, checksum);
    }

    /** 测量 bytes payload，估算包含长度头以排除意外扩容。 @author zn */
    private static final class BytesCodec implements ZeroPayloadCodec<byte[]> {
        /** @return 提供者名；不可为空，线程安全。 */
        @Override public String name() { return "benchmark"; }
        /** @return 消息类型，线程安全。 */
        @Override public Class<byte[]> messageType() { return byte[].class; }
        /** 同步写入，不保留 writer；调用方独占 writer。 */
        @Override public void write(final ZeroWriter writer, final byte[] message) { writer.writeByteArray(message); }
        /** @return 有序可变副本；独占 reader。 */
        @Override public byte[] read(final ZeroReader reader) { return reader.readByteArray(); }
        /** @return 大小估计；线程安全。 */
        @Override public int estimatedSize(final byte[] message) { return message.length + 5; }
    }
}
