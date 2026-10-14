package group.zn.zero.player;

import group.zn.zero.data.repository.RepositoryDefinition;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;

/** 正式玩家档案映射与 codec；供应用具名 RepositoryCatalog 绑定，不选择具体后端。 @author zn */
public final class PlayerProfileRepository {
    /** 不可变定义；schema/codec 版本均为 1。 */
    public static final RepositoryDefinition<Long, PlayerProfile> DEFINITION = RepositoryDefinition.of(
            "player-profiles", Long.class, PlayerProfile.class, new ProfileCodec(), 1);
    private PlayerProfileRepository() { }

    /** 固定字段二进制 codec，实例无状态、线程安全。 @author zn */
    private static final class ProfileCodec implements ZeroPayloadCodec<PlayerProfile> {
        /** @return 稳定 codec 名。 */
        @Override public String name() { return "player-profile-v1"; }
        /** @return 实体类型。 */
        @Override public Class<PlayerProfile> messageType() { return PlayerProfile.class; }
        /** @param writer 独占 writer。 @param value 不可变快照；写入全部字段。 */
        @Override public void write(final ZeroWriter writer, final PlayerProfile value) {
            writer.writeLong(value.uid()); writer.writeLong(value.version());
            writer.writeString(value.name()); writer.writeBoolean(value.online());
        }
        /** @param reader 独占 reader。 @return 不可变快照；畸形字段异常直接传播。 */
        @Override public PlayerProfile read(final ZeroReader reader) {
            return new PlayerProfile(reader.readLong(), reader.readLong(), reader.readString(), reader.readBoolean());
        }
    }
}
