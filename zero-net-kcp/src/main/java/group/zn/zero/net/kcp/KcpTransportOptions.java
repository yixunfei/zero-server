package group.zn.zero.net.kcp;

import java.util.Objects;

/** FEC、UDP 保护和候选路径的可组合配置；线程安全。 @author zn */
public record KcpTransportOptions(int protectionId, boolean allowUnauthenticated, KcpFecOptions fec,
        KcpPathOptions paths, int maxDatagramBytes, long maxFecBytesTotal) {
    /** 校验组合；明文不允许启用路径迁移。 */
    public KcpTransportOptions {
        Objects.requireNonNull(fec); Objects.requireNonNull(paths);
        if (protectionId < 0 || protectionId > 65535 || protectionId == 0 && (!allowUnauthenticated || paths.enabled())
                || maxDatagramBytes < 512 || maxDatagramBytes > 8192 || maxFecBytesTotal < fec.maxBytes()
                || maxFecBytesTotal > (1L << 32)) throw new IllegalArgumentException("invalid KCP transport policy");
    }
    /** @return 默认 HMAC、无 FEC、固定地址配置。 */
    public static KcpTransportOptions defaults() { return of(1, KcpFecOptions.none(), KcpPathOptions.fixed()); }
    /** @param protectionId 0=明文、1=HMAC、2=ChaCha、3=AES。 @return 配置。 */
    public static KcpTransportOptions of(final int protectionId, final KcpFecOptions fec, final KcpPathOptions paths) {
        return new KcpTransportOptions(protectionId, false, fec, paths, 1472, 64L << 20);
    }
}
