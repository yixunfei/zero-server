package group.zn.zero.net.kcp;

/** 显式注册的数据报保护 SPI；每个方向拥有独占上下文。 @author zn */
public interface KcpDatagramProtection {
    /** @return 稳定 wire ID。 */
    int wireId();
    /** @return 认证标签长度。 */
    int tagBytes();
    /** @return 是否认证头部与载荷。 */
    boolean authenticated();
    /** @return 是否加密载荷。 */
    boolean encrypted();
    /** @param key 32 字节方向密钥。 @return 独占上下文。 */
    Context create(byte[] key);
    /** 独占密码上下文。 @author zn */
    interface Context extends AutoCloseable {
        /** 保护数据；nonce 必须唯一。 */
        byte[] seal(byte[] nonce, byte[] aad, byte[] body);
        /** 解保护；认证失败返回 null。 */
        byte[] open(byte[] nonce, byte[] aad, byte[] body);
        /** 清除密钥副本。 */
        @Override void close();
    }
}
