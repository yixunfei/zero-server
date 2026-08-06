package group.zn.zero.protocol.buffer;

import java.nio.ByteBuffer;

/**
 * zero 协议缓冲区轻量基准程序。
 *
 * <p>该类不依赖 JMH，用于在当前仓库环境里快速对比 heap、direct、native 三种缓冲区实现
 * 在典型 payload 序列化场景下的相对表现。基准流程为 writer 复用、写入示例 payload、
 * 物化输出并读回校验。它只用于人工基准，不进入生产代码路径。
 *
 * @author zn
 */
public final class ZeroBufferBenchmark {

    /**
     * 默认基准轮数。
     */
    private static final int DEFAULT_ROUNDS = 5;

    /**
     * 默认预热轮数。
     */
    private static final int DEFAULT_WARMUP_ROUNDS = 5;

    /**
     * 默认单轮迭代次数。
     */
    private static final int DEFAULT_ITERATIONS = 100_000;

    /**
     * 示例 payload 中的字节块。
     */
    private static final byte[] SAMPLE_BYTES = new byte[] {
        3, 1, 4, 1, 5, 9, 2, 6,
        5, 3, 5, 8, 9, 7, 9, 3,
        2, 3, 8, 4, 6, 2, 6, 4,
        3, 3, 8, 3, 2, 7, 9, 5
    };

    /**
     * 示例 payload 中的 int 数组。
     */
    private static final int[] SAMPLE_INTS = new int[] {7, 11, 13, 17, 19, 23, 29, 31};

    /**
     * 示例 payload 中的 long 数组。
     */
    private static final long[] SAMPLE_LONGS = new long[] {
        101L, 103L, 107L, 109L, 113L, 127L, 131L, 137L
    };

    /**
     * 禁止实例化。
     */
    private ZeroBufferBenchmark() {
    }

    /**
     * 程序入口。
     *
     * @param args 可选参数：iterations、warmupRounds、measureRounds。
     */
    public static void main(final String[] args) {
        int iterations = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_ITERATIONS;
        int warmupRounds = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_WARMUP_ROUNDS;
        int measureRounds = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_ROUNDS;

        System.out.println("iterations=" + iterations
                + ", warmupRounds=" + warmupRounds
                + ", measureRounds=" + measureRounds);
        System.out.println();

        runScenario("heap-bytes", ZeroWriter::new, false, iterations, warmupRounds, measureRounds);
        runScenario("direct-bytes", () -> ZeroWriter.direct(256), false, iterations, warmupRounds, measureRounds);
        if (ZeroBuffers.nativeMemoryAvailable()) {
            runScenario("native-bytes", () -> ZeroWriter.nativeMemory(256), false, iterations, warmupRounds, measureRounds);
        }

        System.out.println();

        runScenario("heap-bytebuffer", ZeroWriter::new, true, iterations, warmupRounds, measureRounds);
        runScenario("direct-bytebuffer", () -> ZeroWriter.direct(256), true, iterations, warmupRounds, measureRounds);
        if (ZeroBuffers.nativeMemoryAvailable()) {
            runScenario("native-bytebuffer", () -> ZeroWriter.nativeMemory(256), true, iterations, warmupRounds, measureRounds);
        }
    }

    /**
     * 执行单个基准场景。
     *
     * @param name 场景名称。
     * @param writerFactory 写入器工厂。
     * @param byteBufferMode 是否走 ByteBuffer 输出。
     * @param iterations 每轮迭代次数。
     * @param warmupRounds 预热轮数。
     * @param measureRounds 测量轮数。
     */
    private static void runScenario(
            final String name,
            final WriterFactory writerFactory,
            final boolean byteBufferMode,
            final int iterations,
            final int warmupRounds,
            final int measureRounds) {
        long warmupChecksum = 0L;
        for (int round = 0; round < warmupRounds; round++) {
            warmupChecksum ^= measureRound(writerFactory, byteBufferMode, iterations, true, round);
        }

        long totalNanos = 0L;
        long checksum = warmupChecksum;
        for (int round = 0; round < measureRounds; round++) {
            long start = System.nanoTime();
            checksum ^= measureRound(writerFactory, byteBufferMode, iterations, false, round);
            long elapsed = System.nanoTime() - start;
            totalNanos += elapsed;
        }

        long totalOps = (long) iterations * measureRounds;
        double nanosPerOp = totalOps == 0L ? 0.0D : (double) totalNanos / totalOps;
        double opsPerSec = totalNanos == 0L ? 0.0D : (double) totalOps * 1_000_000_000.0D / totalNanos;
        System.out.printf("%-18s %10.2f ns/op %12.2f ops/s checksum=%d%n",
                name,
                nanosPerOp,
                opsPerSec,
                checksum);
    }

    /**
     * 执行单轮迭代。
     *
     * @param writerFactory 写入器工厂。
     * @param byteBufferMode 是否走 ByteBuffer 输出。
     * @param iterations 迭代次数。
     * @param warmup 是否为预热轮。
     * @param round 当前轮次。
     * @return 轮次校验值。
     */
    private static long measureRound(
            final WriterFactory writerFactory,
            final boolean byteBufferMode,
            final int iterations,
            final boolean warmup,
            final int round) {
        try (ZeroWriter writer = writerFactory.create()) {
            long checksum = 0L;
            for (int index = 0; index < iterations; index++) {
                writer.reset();
                writeSample(writer, round * 31 + index);
                if (byteBufferMode) {
                    ByteBuffer buffer = writer.toByteBuffer();
                    checksum += buffer.remaining();
                    checksum += readSample(new ZeroReader(buffer));
                } else {
                    byte[] bytes = writer.toByteArray();
                    checksum += bytes.length;
                    checksum += readSample(new ZeroReader(bytes));
                }
            }
            if (warmup) {
                return checksum ^ 0x5A5A5A5AL;
            }
            return checksum;
        }
    }

    /**
     * 写入一份示例 payload。
     *
     * @param writer 写入器；不可为空。
     * @param sequence 序号，用于避免常量折叠。
     */
    private static void writeSample(final ZeroWriter writer, final int sequence) {
        writer.writeInt(sequence);
        writer.writeLong(0x1122_3344_5566_7788L ^ sequence);
        writer.writeFloat(1.5F + (sequence & 3));
        writer.writeDouble(-2.25D - (sequence & 1));
        writer.writeString("request-" + (sequence & 1023));
        writer.writeNullableString((sequence & 1) == 0 ? null : "payload");
        writer.writeByteArray(SAMPLE_BYTES);
        writer.writeIntArray(SAMPLE_INTS);
        writer.writeLongArray(SAMPLE_LONGS);
        writer.writePresenceBits(true, false, true, true, false, false, true, false, true);
    }

    /**
     * 读取一份示例 payload 并返回校验值。
     *
     * @param reader 读取器；不可为空。
     * @return 校验值。
     */
    private static long readSample(final ZeroReader reader) {
        long checksum = 0L;
        checksum += reader.readInt();
        checksum += reader.readLong();
        checksum += Float.floatToIntBits(reader.readFloat());
        checksum += Double.doubleToLongBits(reader.readDouble());
        checksum += reader.readString().length();
        String nullable = reader.readNullableString();
        checksum += nullable == null ? 7L : nullable.length();
        checksum += reader.readByteArray().length;
        checksum += reader.readIntArray().length;
        checksum += reader.readLongArray().length;
        checksum += reader.readPresenceBits().length;
        return checksum;
    }

    /**
     * 写入器工厂。
     */
    @FunctionalInterface
    private interface WriterFactory {

        /**
         * 创建写入器。
         *
         * @return 写入器；不可为空。
         */
        ZeroWriter create();
    }
}
