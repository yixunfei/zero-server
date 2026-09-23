package group.zn.zero.net;

import java.util.Objects;

/**
 * 网络服务器配置。
 *
 * @param serverType 服务器类型。
 * @param host 监听主机。
 * @param port 监听端口，0 表示由系统选择。
 * @param bossThreads 接收连接线程数。
 * @param workerThreads IO 工作线程数。
 * @param maxFrameLength 最大 frame 或内容长度。
 * @param codecType 网络编解码类型。
 * @author zn
 */
public record ServerOptions(
        ServerType serverType,
        String host,
        int port,
        int bossThreads,
        int workerThreads,
        int maxFrameLength,
        ServerCodecType codecType,
        NetworkTuning tuning) {

    /**
     * 使用默认资源预算创建配置；参数约束同完整构造器，线程安全。
     * @param serverType 服务类型。
     * @param host 主机。
     * @param port 端口。
     * @param bossThreads 接收线程数。
     * @param workerThreads IO 线程数。
     * @param maxFrameLength 最大完整帧字节数，TCP 含四字节长度前缀。
     * @param codecType codec 类型。
     */
    public ServerOptions(final ServerType serverType, final String host, final int port,
            final int bossThreads, final int workerThreads, final int maxFrameLength, final ServerCodecType codecType) {
        this(serverType, host, port, bossThreads, workerThreads, maxFrameLength, codecType, NetworkTuning.defaults());
    }

    /**
     * 默认最大 frame 长度。
     */
    public static final int DEFAULT_MAX_FRAME_LENGTH = 16 * 1024 * 1024;

    /**
     * 创建网络服务器配置。
     *
     * @throws NullPointerException 当服务器类型、主机或编解码类型为空时抛出。
     * @throws IllegalArgumentException 当端口、线程数或长度非法时抛出。
     */
    public ServerOptions {
        Objects.requireNonNull(serverType, "serverType");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(codecType, "codecType");
        Objects.requireNonNull(tuning, "tuning");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        if (bossThreads <= 0) {
            throw new IllegalArgumentException("bossThreads must be positive");
        }
        if (workerThreads <= 0) {
            throw new IllegalArgumentException("workerThreads must be positive");
        }
        if (maxFrameLength <= 0) {
            throw new IllegalArgumentException("maxFrameLength must be positive");
        }
    }

    /**
     * 创建 TCP 默认配置。
     *
     * @param host 监听主机；不可为空。
     * @param port 监听端口，0 表示由系统选择。
     * @return TCP 配置；不可为空；线程安全。
     */
    public static ServerOptions tcp(final String host, final int port) {
        return defaults(ServerType.TCP, host, port, ServerCodecType.ZERO_BINARY);
    }

    /**
     * 创建 UDP 默认配置。
     *
     * @param host 监听主机；不可为空。
     * @param port 监听端口，0 表示由系统选择。
     * @return UDP 配置；不可为空；线程安全。
     */
    public static ServerOptions udp(final String host, final int port) {
        return defaults(ServerType.UDP, host, port, ServerCodecType.ZERO_BINARY);
    }

    /**
     * 创建 HTTP 默认配置。
     *
     * @param host 监听主机；不可为空。
     * @param port 监听端口，0 表示由系统选择。
     * @return HTTP 配置；不可为空；线程安全。
     */
    public static ServerOptions http(final String host, final int port) {
        return defaults(ServerType.HTTP, host, port, ServerCodecType.HTTP);
    }

    /**
     * 创建 KCP 默认配置。
     *
     * @param host 监听主机；不可为空。
     * @param port 监听端口，0 表示由系统选择。
     * @return KCP 配置；不可为空；线程安全。
     */
    public static ServerOptions kcp(final String host, final int port) {
        return defaults(ServerType.KCP, host, port, ServerCodecType.ZERO_BINARY);
    }

    private static ServerOptions defaults(
            final ServerType serverType,
            final String host,
            final int port,
            final ServerCodecType codecType) {
        int workers = Math.max(1, Runtime.getRuntime().availableProcessors());
        return new ServerOptions(serverType, host, port, 1, workers, DEFAULT_MAX_FRAME_LENGTH, codecType);
    }

    /**
     * 返回更新最大 frame 长度后的配置。
     *
     * @param length 最大 frame 长度。
     * @return 新配置；不可为空；线程安全。
     */
    public ServerOptions withMaxFrameLength(final int length) {
        return new ServerOptions(serverType, host, port, bossThreads, workerThreads, length, codecType, tuning);
    }

    /**
     * 返回更新 IO 线程数后的配置。
     *
     * @param boss 接收连接线程数。
     * @param worker IO 工作线程数。
     * @return 新配置；不可为空；线程安全。
     */
    public ServerOptions withIoThreads(final int boss, final int worker) {
        return new ServerOptions(serverType, host, port, boss, worker, maxFrameLength, codecType, tuning);
    }

    /** @param value 不可变资源设置。 @return 更新后的独立配置；线程安全。 */
    public ServerOptions withTuning(final NetworkTuning value) {
        return new ServerOptions(serverType, host, port, bossThreads, workerThreads, maxFrameLength, codecType, value);
    }

    /**
     * 返回监听地址。
     *
     * @return 监听地址；不可为空；线程安全。
     */
    public String bindAddress() {
        return host + ":" + port;
    }
}
