package group.zn.zero.examples.business;

import group.zn.zero.data.repository.RepositoryDefinition;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;

/** 奖励聚合的正式 envelope 定义；显式 schema 3，不隐式读取旧示例格式。 @author zn */
public final class DailyRewardRepository {
    /** 由组合根绑定 game 角色，业务不选择后端。 */
    public static final RepositoryDefinition<Long, DailyRewardInventory> DEFINITION = RepositoryDefinition.of(
            "daily-rewards", Long.class, DailyRewardInventory.class, new Codec(), 3);
    private DailyRewardRepository() { }
    /** 无状态、线程安全的完整聚合 codec。 @author zn */
    private static final class Codec implements ZeroPayloadCodec<DailyRewardInventory> {
        /** @return codec 名。 */
        @Override public String name() { return "daily-reward-v3"; }
        /** @return 类型。 */
        @Override public Class<DailyRewardInventory> messageType() { return DailyRewardInventory.class; }
        /** @param writer 独占 writer。 @param value 快照；写入全部聚合字段。 */
        @Override public void write(final ZeroWriter writer, final DailyRewardInventory value) {
            writer.writeLong(value.id()); writer.writeLong(value.version()); writer.writeString(value.itemId());
            writer.writeInt(value.quantity()); writer.writeString(value.claimDay()); writer.writeString(value.claimKey());
            writer.writeBoolean(value.pendingNotification()); writer.writeString(value.notificationTrace());
        }
        /** @param reader 独占 reader。 @return 不可变聚合；畸形字段异常直接传播。 */
        @Override public DailyRewardInventory read(final ZeroReader reader) {
            return new DailyRewardInventory(reader.readLong(), reader.readLong(), reader.readString(), reader.readInt(),
                    reader.readString(), reader.readString(), reader.readBoolean(), reader.readString());
        }
    }
}
