package group.zn.zero.benchmark.performance;

/** 排除客户端首次 TLS 上下文初始化，保留同起点 socket connect/握手/首响应测量。 @author zn */
public final class WarmConnectionBurstProbe {
    private WarmConnectionBurstProbe() { }

    /**
     * 在共同计时起点之前完成 TLS 上下文和 socket 类初始化，不建立预热连接。
     * @param args 与 ConnectionBurstProbe 相同；每次为独立 JVM。
     * @throws Exception 初始化、负载或资源关闭失败时非零退出。
     */
    public static void main(final String[] args) throws Exception {
        try (var initialization = LoadTls.socket()) {
            if (initialization.isConnected()) throw new IllegalStateException("unexpected warmup connection");
        }
        ConnectionBurstProbe.main(args);
    }
}
