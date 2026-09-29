package group.zn.zero.net.kcp;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/** 可迁移的会话元数据；不包含业务帧和密钥，业务快照由应用控制。 @author zn */
public record KcpSessionSnapshot(int conv, long generation, String owner, String subject,
        InetSocketAddress remote, Instant expiresAt, byte[] receiveWindow, long sentSequence,
        long receivedSequence) {
    /** 构造复制数组并校验所有权元数据。 */
    public KcpSessionSnapshot {
        Objects.requireNonNull(owner); Objects.requireNonNull(subject); Objects.requireNonNull(remote);
        Objects.requireNonNull(expiresAt); Objects.requireNonNull(receiveWindow);
        if (conv == 0 || generation < 1 || !owner.matches("[A-Za-z0-9._-]{1,64}")
                || subject.isBlank() || subject.length() > 512 || remote.isUnresolved() || remote.getPort() < 1
                || receiveWindow.length != 8 || sentSequence < 0 || receivedSequence < 0) {
            throw new IllegalArgumentException("invalid KCP session snapshot");
        }
        receiveWindow = receiveWindow.clone();
    }
    /** @return 独立去重窗口副本。 */
    @Override public byte[] receiveWindow() { return receiveWindow.clone(); }
}
