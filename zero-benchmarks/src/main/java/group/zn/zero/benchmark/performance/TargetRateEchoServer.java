package group.zn.zero.benchmark.performance;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.scheduler.ExecutorActorScheduler;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.netty.NettyTcpServer;
import group.zn.zero.protocol.ProtocolFrame;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 独立 TCP→Actor→TCP 完成链路负载服务；仅供本地性能证据，不是业务服务。 @author zn */
public final class TargetRateEchoServer {
    private TargetRateEchoServer() { }
    /**
     * 启动本地服务，写出实际端口，直到停止文件出现；不访问外部服务。
     * @param args portFile stopFile [gameChurn]；文件路径由负载脚本指定。
     * @throws Exception 绑定、文件或关闭失败，进程非零退出。
     */
    public static void main(final String[] args) throws Exception {
        Path portFile = Path.of(args[0]);
        Path stopFile = Path.of(args[1]);
        var workers = Executors.newFixedThreadPool(4);
        var scheduler = new ExecutorActorScheduler(workers);
        LoadPayload payload = LoadPayload.configured();
        scheduler.register(Request.class, (context, message) -> {
            Request request = (Request) message.payload();
            request.result.complete(List.of(payload.response(request.frame)));
            return CompletableFuture.completedFuture(null);
        });
        var defaults = group.zn.zero.net.NetworkTuning.defaults();
        var tuning = new group.zn.zero.net.NetworkTuning(defaults.transport(), Integer.getInteger("zero.load.backlog", 128),
                defaults.writeLowWaterMark(), defaults.writeHighWaterMark(), defaults.maxPendingBytesPerConnection(),
                defaults.maxPendingBytesTotal(), Integer.getInteger("zero.load.flush", 0));
        var options = ServerOptions.tcp("127.0.0.1", 0).withIoThreads(1, 4)
                .withTuning(tuning).withMaxFrameLength(1024 * 1024);
        group.zn.zero.net.ServerFrameHandler handler = (connection, frame) -> {
                    var result = new CompletableFuture<List<ProtocolFrame>>();
                    scheduler.dispatch(new ActorMessage(LaneKey.custom(connection.connectionId()), new Request(frame, result)))
                            .whenComplete((ignored, failure) -> {
                                if (failure != null) result.completeExceptionally(failure);
                            });
                    return result;
                };
        var tls = LoadTls.serverContext();
        var server = tls == null ? new NettyTcpServer(options, handler, Runnable::run)
                : new NettyTcpServer(options, new group.zn.zero.protocol.codec.ZeroBinaryFrameCodec(), handler,
                        new group.zn.zero.net.ConnectionListener() { }, Runnable::run, null, tls);
        try {
            server.start();
            Files.writeString(portFile, Integer.toString(server.boundPort()));
            int seconds = 0;
            while (!Files.exists(stopFile)) {
                if (seconds++ % 10 == 0) LoadResources.sample("server");
                if (args.length > 2 && Boolean.parseBoolean(args[2])) GameResourceChurn.cycle();
                Thread.sleep(1000);
            }
        } finally {
            server.stop();
            if ((Object) scheduler instanceof AutoCloseable closeable) closeable.close();
            workers.shutdown();
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) workers.shutdownNow();
            LoadResources.sample("server-final");
        }
    }
    /** 自持有业务帧及完成信号，仅供本次请求。 @author zn */
    private record Request(ProtocolFrame frame, CompletableFuture<List<ProtocolFrame>> result) { }
}
