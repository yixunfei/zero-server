package group.zn.zero.net.kcp;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.lifecycle.AbstractLifecycle;
import group.zn.zero.net.IServer;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.ServerType;
import group.zn.zero.net.error.NetErrorCode;
import java.util.Objects;

/**
 * KCP 服务器占位实现。
 *
 * <p>当前仅保留 KCP Server 边界，不直接把第三方 KCP 实现引入 `zero-net`。
 * 后续实现应参考 java-Kcp 和 KCP/TCP 协作登录方案，并单独验证依赖与性能参数。
 *
 * @author zn
 */
public final class UnsupportedKcpServer extends AbstractLifecycle implements IServer {

    /**
     * 服务器配置。
     */
    private final ServerOptions options;

    /**
     * 创建 KCP 占位服务器。
     *
     * @param options 服务器配置；不可为空。
     */
    public UnsupportedKcpServer(final ServerOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        if (options.serverType() != ServerType.KCP) {
            throw new IllegalArgumentException("UnsupportedKcpServer only supports KCP options");
        }
    }

    /**
     * 返回监听地址。
     *
     * @return 监听地址；不可为空；线程安全。
     */
    @Override
    public String bindAddress() {
        return options.bindAddress();
    }

    /**
     * 返回服务器类型。
     *
     * @return KCP；不可为空；线程安全。
     */
    @Override
    public ServerType serverType() {
        return ServerType.KCP;
    }

    /**
     * 启动 KCP 服务器。
     *
     * @throws ZeroException 当前 KCP 实现未接入时抛出。
     */
    @Override
    protected void doStart() {
        throw ZeroException.of(
                NetErrorCode.KCP_NOT_IMPLEMENTED,
                "KCP server boundary exists, implementation must be provided by a dedicated adapter",
                null);
    }
}
