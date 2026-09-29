package group.zn.zero.net.kcp;

/** 单会话独占的擦除编码器；不拥有输入数组。 @author zn */
public interface KcpFecStrategy {
    /** @return 稳定算法 wire ID。 */
    int wireId();
    /** @return 数据 shard 数。 */
    int dataShards();
    /** @return 校验 shard 数。 */
    int parityShards();
    /** 填充校验 shard；输入数组必须全部等长。 */
    void encode(byte[][] shards);
    /** 恢复缺失 shard；不足时返回 false。 */
    boolean recover(byte[][] shards, boolean[] present);
}
