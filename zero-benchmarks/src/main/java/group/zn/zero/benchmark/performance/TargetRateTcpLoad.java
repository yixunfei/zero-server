package group.zn.zero.benchmark.performance;

import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * 独立进程的目标速率 TCP 客户端；延迟从计划发送时间到完整响应，计入调度/排队延迟。
 * IO 使用虚拟线程；最多 16384 个在途请求，超限计入拒绝，不伪装成完成吞吐。
 * @author zn
 */
public final class TargetRateTcpLoad {
    /** 帧编解码器。 */
    private final ZeroBinaryFrameCodec codec = new ZeroBinaryFrameCodec();
    /** 显式选择的 echo/生成 DTO 消息负载。 */
    private final LoadPayload payloadCodec = LoadPayload.configured();
    /** 全局在途表。 */
    private final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<>();
    /** 实测延迟。 */
    private final LatencyHistogram latency = new LatencyHistogram();
    /** 实际成功完成数。 */
    private final LongAdder completed = new LongAdder();
    /** 写失败或断连丢失数。 */
    private final LongAdder failed = new LongAdder();
    /** 请求超时数。 */
    private final LongAdder timedOut = new LongAdder();
    /** 客户端在途预算拒绝数。 */
    private final LongAdder rejected = new LongAdder();
    /** 超时后的响应或重复响应数。 */
    private final LongAdder unexpectedResponses = new LongAdder();
    /** 成功重建连接次数。 */
    private long reconnects;
    /** 显式慢消费者实验；默认不延迟读取，不改变生产代码。 */
    private final long readDelayNanos = Long.getLong("zero.load.readDelayMillis", 0L) * 1_000_000L;
    /** 预热和测量在同一 JVM 内使用独立计数，保留客户端 JIT 状态。 */
    private final boolean warmup;
    /** 实际发送阶段的墙钟起点，用于对齐独立 OS/GC 采样。 */
    private long startedAtMillis;
    private TargetRateTcpLoad(final boolean warmup) { this.warmup = warmup; }

    /**
     * 执行独立客户端负载并输出 JSONL；固定请求截止 3 秒。
     * @param args port connections rate seconds payloadBytes churn；churn 为 true/false。
     * @throws Exception 建连、协议或关闭失败，结果必须与服务端日志一起分析。
     */
    public static void main(final String[] args) throws Exception {
        int warmupSeconds = Integer.getInteger("zero.load.warmupSeconds", 0);
        if (warmupSeconds > 0) {
            new TargetRateTcpLoad(true).run(Integer.parseInt(args[0]), Integer.parseInt(args[1]),
                    Integer.parseInt(args[2]), warmupSeconds, Integer.parseInt(args[4]), false);
        }
        new TargetRateTcpLoad(false).run(Integer.parseInt(args[0]), Integer.parseInt(args[1]),
                Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]), Boolean.parseBoolean(args[5]));
    }

    private void run(final int port, final int connectionCount, final int rate, final int seconds,
            final int payloadSize, final boolean churn) throws Exception {
        if (connectionCount <= 0 || rate <= 0 || seconds <= 0 || payloadSize < 16) throw new IllegalArgumentException("load parameters");
        List<Client> clients = new ArrayList<>();
        long offered = 0;
        long started = 0;
        long sendingFinished = 0;
        try {
            for (int i = 0; i < connectionCount; i++) clients.add(new Client(port));
            LoadResources.sample(warmup ? "client-warmup-ready" : "client-ready");
            startedAtMillis = System.currentTimeMillis();
            started = System.nanoTime();
            long deadline = started + seconds * 1_000_000_000L;
            long nextSample = started;
            long nextExpiry = started + 100_000_000L;
            long nextChurn = started + 5_000_000_000L;
            while (System.nanoTime() < deadline) {
                long due = started + offered * 1_000_000_000L / rate;
                if (due >= deadline) break;
                long wait = due - System.nanoTime();
                if (wait > 0) {
                    // Windows 的 park 精度可能远粗于目标间隔；最后 2ms 使用专用生产者核。
                    if (wait > 2_000_000) LockSupport.parkNanos(wait - 1_000_000);
                    else Thread.onSpinWait();
                    continue;
                }
                Client client = clients.get((int) (offered % connectionCount));
                long sequence = offered++;
                if (pending.size() >= 16384 || client.closed.get()) rejected.increment();
                else send(client, sequence, due, payloadSize);
                long now = System.nanoTime();
                if (now >= nextExpiry) {
                    expire();
                    nextExpiry = now + 100_000_000L;
                }
                if (now >= nextSample) {
                    LoadResources.sample(warmup ? "client-warmup" : "client");
                    progress(offered);
                    nextSample = System.nanoTime() + 10_000_000_000L;
                }
                if (churn && System.nanoTime() >= nextChurn) {
                    reconnect(clients, port, (int) (reconnects % connectionCount));
                    nextChurn = System.nanoTime() + 5_000_000_000L;
                }
            }
            sendingFinished = System.nanoTime();
            long drainDeadline = sendingFinished + 4_000_000_000L;
            // 在途移除发生在读线程提交统计之前，不能仅以 pending 为空判断汇总已稳定。
            while (accounted() != offered && System.nanoTime() < drainDeadline) {
                expire();
                Thread.sleep(10);
            }
            if (accounted() != offered) throw new IllegalStateException("load result accounting did not settle");
            double elapsed = (System.nanoTime() - started) / 1_000_000_000.0;
            report(offered, seconds * (long) rate, elapsed, (sendingFinished - started) / 1_000_000_000.0);
        } finally {
            for (Client client : clients) client.close();
            LoadResources.sample(warmup ? "client-warmup-final" : "client-final");
        }
    }

    private void send(final Client client, final long sequence, final long due, final int payloadSize) {
        byte[] encoded = codec.encode(payloadCodec.request(sequence, due, payloadSize));
        byte[] wire = ByteBuffer.allocate(encoded.length + 4).putInt(encoded.length).put(encoded).array();
        Pending request = new Pending(client, due);
        pending.put(sequence, request);
        client.inFlight.incrementAndGet();
        try { client.output.write(wire); }
        catch (IOException failure) {
            if (remove(sequence, request)) failed.increment();
        }
    }

    private void received(final byte[] encoded) {
        ProtocolFrame frame = codec.decode(encoded);
        long sequence = payloadCodec.sequence(frame);
        Pending request = pending.get(sequence);
        if (request == null || !remove(sequence, request)) {
            unexpectedResponses.increment();
            return;
        }
        long elapsed = System.nanoTime() - request.due;
        if (elapsed > 3_000_000_000L) timedOut.increment();
        else {
            latency.record(elapsed);
            completed.increment();
        }
    }

    private boolean remove(final long sequence, final Pending request) {
        if (!pending.remove(sequence, request)) return false;
        request.client.inFlight.decrementAndGet();
        return true;
    }

    private long accounted() {
        return completed.sum() + failed.sum() + timedOut.sum() + rejected.sum();
    }

    private void expire() {
        long now = System.nanoTime();
        pending.forEach((id, request) -> {
            if (now - request.due > 3_000_000_000L && remove(id, request)) timedOut.increment();
        });
    }

    private void reconnect(final List<Client> clients, final int port, final int index) throws IOException {
        Client old = clients.get(index);
        if (old.inFlight.get() != 0) return;
        Client replacement = new Client(port);
        clients.set(index, replacement);
        old.close();
        reconnects++;
    }

    private void progress(final long offered) {
        System.out.printf("{\"kind\":\"%s\",\"offered\":%d,\"completed\":%d,\"failed\":%d,"
                + "\"timeouts\":%d,\"rejected\":%d,\"pending\":%d,\"reconnects\":%d}%n",
                warmup ? "warmup-progress" : "progress", offered, completed.sum(), failed.sum(), timedOut.sum(),
                rejected.sum(), pending.size(), reconnects);
    }

    private void report(final long offered, final long planned, final double seconds, final double sendingSeconds) {
        System.out.printf(java.util.Locale.ROOT,
                "{\"kind\":\"%s\",\"startedAtMillis\":%d,\"finishedAtMillis\":%d,\"planned\":%d,\"offered\":%d,\"completed\":%d,\"failed\":%d,"
                + "\"timeouts\":%d,\"rejected\":%d,\"pending\":%d,\"unexpectedResponses\":%d,\"reconnects\":%d,"
                + "\"seconds\":%.6f,\"sendingSeconds\":%.6f,\"completedPerSecond\":%.3f,\"p50ns\":%d,"
                + "\"p95ns\":%d,\"p99ns\":%d,\"p999ns\":%d,\"maxNs\":%d}%n",
                warmup ? "warmup-result" : "result", startedAtMillis, System.currentTimeMillis(),
                planned, offered, completed.sum(), failed.sum(), timedOut.sum(), rejected.sum(), pending.size(),
                unexpectedResponses.sum(), reconnects, seconds, sendingSeconds, completed.sum() / seconds,
                latency.percentile(.5), latency.percentile(.95), latency.percentile(.99), latency.percentile(.999), latency.maximum());
    }

    /** 请求身份，仅在途期间持有所属连接。 @author zn */
    private record Pending(Client client, long due) { }

    /** 单连接的唯一读线程；主驱动线程负责写入。 @author zn */
    private final class Client implements AutoCloseable {
        /** 本地测试 socket。 */
        private final Socket socket;
        /** 帧输入。 */
        private final DataInputStream input;
        /** 帧输出。 */
        private final DataOutputStream output;
        /** 关闭标志。 */
        private final AtomicBoolean closed = new AtomicBoolean();
        /** 精确在途数，驱动用于安全重连。 */
        private final AtomicInteger inFlight = new AtomicInteger();
        private Client(final int port) throws IOException {
            socket = LoadTls.socket();
            try {
                int receiveBuffer = Integer.getInteger("zero.load.receiveBufferBytes", 0);
                if (receiveBuffer > 0) socket.setReceiveBufferSize(receiveBuffer);
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 3000);
                socket.setSoTimeout(3000);
                if (socket instanceof javax.net.ssl.SSLSocket tls) tls.startHandshake();
                socket.setSoTimeout(0);
                socket.setTcpNoDelay(true);
                input = new DataInputStream(socket.getInputStream());
                output = new DataOutputStream(socket.getOutputStream());
            } catch (IOException failure) {
                try { socket.close(); }
                catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
            Thread.ofVirtual().name("tcp-load-reader").start(this::read);
        }
        private void read() {
            try {
                while (!closed.get()) {
                    if (readDelayNanos > 0) LockSupport.parkNanos(readDelayNanos);
                    int length = input.readInt();
                    if (length < 0 || length > 1024 * 1024) throw new IOException("invalid response length");
                    byte[] bytes = new byte[length];
                    input.readFully(bytes);
                    received(bytes);
                }
            } catch (IOException | RuntimeException failure) {
                pending.forEach((id, request) -> {
                    if (request.client == this && remove(id, request)) failed.increment();
                });
                // 请求进入失败计数；关闭连接，不把读失败当作成功继续执行。
                closed.set(true);
                try { socket.close(); }
                catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
                if (!(failure instanceof java.io.EOFException) && !(failure instanceof java.net.SocketException)) {
                    failure.printStackTrace(System.err);
                }
            }
        }
        /** 关闭读线程及 socket；不吞掉资源关闭异常。 @throws IOException socket 关闭失败。 */
        @Override public void close() throws IOException {
            closed.set(true);
            socket.close();
        }
    }
}
