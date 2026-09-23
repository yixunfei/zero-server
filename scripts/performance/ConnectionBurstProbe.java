package group.zn.zero.benchmark.performance;

import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/** 同一时刻释放有界建连突发，分别观测 connect 和首个业务响应；不推断 OS 实际队列长度。 @author zn */
public final class ConnectionBurstProbe {
    private ConnectionBurstProbe() { }
    /** @param args port connections；有界单次突发。 @throws Exception 工作线程或资源失败。 */
    public static void main(final String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int connections = Integer.parseInt(args[1]);
        var connectLatency = new LatencyHistogram();
        var responseLatency = new LatencyHistogram();
        var connected = new LongAdder();
        var completed = new LongAdder();
        var failed = new LongAdder();
        var timeouts = new LongAdder();
        var ready = new CountDownLatch(connections);
        var start = new CountDownLatch(1);
        var payload = LoadPayload.configured();
        long[] planned = new long[1];
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < connections; i++) {
                final int id = i;
                tasks.add(workers.submit(() -> {
                    ready.countDown();
                    start.await();
                    try (var socket = LoadTls.socket()) {
                        socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                        connected.increment();
                        connectLatency.record(System.nanoTime() - planned[0]);
                        socket.setSoTimeout(3000);
                        if (socket instanceof javax.net.ssl.SSLSocket tls) tls.startHandshake();
                        var codec = new ZeroBinaryFrameCodec();
                        byte[] bytes = codec.encode(payload.request(id, planned[0], 64));
                        var output = new DataOutputStream(socket.getOutputStream());
                        output.writeInt(bytes.length);
                        output.write(bytes);
                        output.flush();
                        var input = new DataInputStream(socket.getInputStream());
                        int length = input.readInt();
                        if (length < 0 || length > 1024 * 1024) throw new java.io.IOException("response length");
                        if (payload.sequence(codec.decode(input.readNBytes(length))) != id) throw new java.io.IOException("correlation");
                        responseLatency.record(System.nanoTime() - planned[0]);
                        completed.increment();
                    } catch (SocketTimeoutException timeout) { timeouts.increment(); }
                    catch (java.io.IOException | RuntimeException failure) { failed.increment(); }
                    return null;
                }));
            }
            try {
                if (!ready.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("burst producer saturated before start");
                planned[0] = System.nanoTime();
                start.countDown();
                for (var task : tasks) task.get(15, TimeUnit.SECONDS);
            } finally { start.countDown(); }
        }
        System.out.printf("{\"kind\":\"result\",\"offered\":%d,\"connected\":%d,\"completed\":%d,"
                + "\"failed\":%d,\"timeouts\":%d,\"connectP99ns\":%d,\"responseP99ns\":%d}%n",
                connections, connected.sum(), completed.sum(), failed.sum(), timeouts.sum(),
                connectLatency.percentile(.99), responseLatency.percentile(.99));
    }
}
