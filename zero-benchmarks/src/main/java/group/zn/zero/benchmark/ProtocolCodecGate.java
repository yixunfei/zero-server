package group.zn.zero.benchmark;

import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ProtocolFrameCodec;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import java.util.Arrays;

/**
 * Short, deterministic local performance gate for the protocol frame codec.
 *
 * <p>This is a smoke-sized baseline, not a capacity or production benchmark.</p>
 */
public final class ProtocolCodecGate {

    private static final int DEFAULT_WARMUPS = 3;
    private static final int DEFAULT_MEASUREMENTS = 5;
    private static final int DEFAULT_OPERATIONS = 10_000;
    private static final long DEFAULT_MAX_NANOS_PER_OPERATION = 100_000L;
    private static final long DEFAULT_MIN_OPERATIONS_PER_SECOND = 5_000L;
    private static final int PROTOCOL_ID = 1001;
    private static final int PROTOCOL_VERSION = 1;

    private ProtocolCodecGate() {
    }

    /**
     * Runs the local gate and exits non-zero when a configured limit is exceeded.
     *
     * @param args optional {@code --warmups=N}, {@code --measurements=N},
     *             {@code --operations=N}, {@code --max-ns=N}, and {@code --min-throughput=N}
     */
    public static void main(final String[] args) {
        final String[] arguments = args == null ? new String[0] : args;
        if (hasFlag(arguments, "--help", "-h")) {
            printHelp();
            return;
        }
        int warmups = option(arguments, "--warmups", DEFAULT_WARMUPS);
        int measurements = option(arguments, "--measurements", DEFAULT_MEASUREMENTS);
        int operations = option(arguments, "--operations", DEFAULT_OPERATIONS);
        long maxNanos = option(arguments, "--max-ns", DEFAULT_MAX_NANOS_PER_OPERATION);
        long minThroughput = option(arguments, "--min-throughput", DEFAULT_MIN_OPERATIONS_PER_SECOND);
        requirePositive(warmups, "warmups");
        requirePositive(measurements, "measurements");
        requirePositive(operations, "operations");
        requirePositive(maxNanos, "max-ns");
        requirePositive(minThroughput, "min-throughput");

        ProtocolFrameCodec codec = new ZeroBinaryFrameCodec();
        ProtocolFrame frame = new ProtocolFrame(PROTOCOL_ID, PROTOCOL_VERSION, 0,
                new byte[] {1, 2, 3, 4}, payload());
        byte[] encoded = codec.encode(frame);
        int checksum = 0;
        for (int i = 0; i < warmups; i++) {
            checksum ^= runBatch(codec, encoded, operations);
        }
        long totalNanos = 0L;
        for (int i = 0; i < measurements; i++) {
            long started = System.nanoTime();
            checksum ^= runBatch(codec, encoded, operations);
            totalNanos += System.nanoTime() - started;
        }
        long measuredOperations = (long) measurements * operations;
        double nanosPerOperation = (double) totalNanos / measuredOperations;
        double throughput = measuredOperations * 1_000_000_000.0 / totalNanos;
        boolean pass = nanosPerOperation <= maxNanos && throughput >= minThroughput;
        System.out.printf("protocol-codec-gate=%s|warmups=%d|measurements=%d|operations=%d|"
                        + "totalNs=%d|nsPerOp=%.1f|opsPerSec=%.1f|payloadBytes=%d|checksum=%d|"
                        + "maxNs=%d|minOpsPerSec=%d%n",
                pass ? "ok" : "failed", warmups, measurements, operations, totalNanos,
                nanosPerOperation, throughput, encoded.length, checksum, maxNanos, minThroughput);
        if (!pass) {
            System.err.printf("Protocol codec performance gate failed: ns/op %.1f (limit %d), "
                            + "ops/s %.1f (minimum %d).%n",
                    nanosPerOperation, maxNanos, throughput, minThroughput);
            System.exit(1);
        }
    }

    private static int runBatch(final ProtocolFrameCodec codec, final byte[] encoded,
                                final int operations) {
        int checksum = 0;
        for (int i = 0; i < operations; i++) {
            ProtocolFrame decoded = codec.decode(encoded);
            checksum ^= decoded.protocolId() ^ decoded.payload().length;
        }
        return checksum;
    }

    private static byte[] payload() {
        byte[] payload = new byte[128];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31 + 7);
        }
        return payload;
    }

    private static int option(final String[] args, final String name, final int fallback) {
        return (int) option(args, name, (long) fallback);
    }

    private static long option(final String[] args, final String name, final long fallback) {
        for (String arg : args) {
            if (arg == null) {
                continue;
            }
            if (arg.startsWith(name + "=")) {
                try {
                    return Long.parseLong(arg.substring(name.length() + 1));
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("Invalid " + arg, ex);
                }
            }
        }
        return fallback;
    }

    private static void requirePositive(final long value, final String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static boolean hasFlag(final String[] args, final String... flags) {
        if (args == null) {
            return false;
        }
        return Arrays.stream(args).anyMatch(arg -> Arrays.stream(flags).anyMatch(arg::equals));
    }

    private static void printHelp() {
        System.out.println("Usage: mvn -Pperformance-gate -pl zero-benchmarks -am verify");
        System.out.println("Options: --warmups=N --measurements=N --operations=N --max-ns=N"
                + " --min-throughput=N");
        System.out.println("Defaults: 3 warmups, 5 measurements, 10000 operations/batch, "
                + "max 100000 ns/op, min 5000 ops/s.");
        System.out.println("This is a short local smoke gate; it makes no production capacity claim.");
    }
}
