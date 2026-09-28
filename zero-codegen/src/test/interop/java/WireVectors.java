import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.standard.dto.CurrencyType;
import group.zn.zero.standard.dto.PlayerCurrencyDTO;
import group.zn.zero.standard.dto.PlayerPlayerSnapshotProtocolDTO;
import group.zn.zero.standard.dto.PlayerSnapshotDTO;
import group.zn.zero.standard.dto.RoleClass;
import group.zn.zero.standard.dto.RoleCreateRoleProtocolDTO;
import group.zn.zero.standard.dto.WireLimitsDTO;
import group.zn.zero.standard.dto.codec.WireLimitsDTOCodec;
import group.zn.zero.standard.dto.codec.PlayerPlayerSnapshotProtocolDTOCodec;
import group.zn.zero.standard.dto.codec.RoleCreateRoleProtocolDTOCodec;
import java.util.HexFormat;

/** Compiled against freshly generated server sources by smoke-interop.ps1. */
public final class WireVectors {
    private WireVectors() {
    }

    public static void main(final String[] args) {
        runVectors();
        boundaries();
        generatedLimits();
    }

    private static void runVectors() {
        RoleCreateRoleProtocolDTO role = new RoleCreateRoleProtocolDTO();
        role.uid = -1234567890123L;
        role.roleName = "Zoe";
        role.roleClass = RoleClass.MAGE;
        role.traceId = "trace-42";
        try (ZeroWriter writer = new ZeroWriter()) {
            RoleCreateRoleProtocolDTOCodec.INSTANCE.write(writer, role);
            byte[] bytes = writer.toByteArray();
            RoleCreateRoleProtocolDTO read = RoleCreateRoleProtocolDTOCodec.INSTANCE.read(new ZeroReader(bytes));
            if (read.uid != role.uid || !read.roleName.equals(role.roleName)
                    || read.roleClass != role.roleClass || !read.traceId.equals(role.traceId)) {
                throw new AssertionError("role round trip failed");
            }
            System.out.println("role=" + HexFormat.of().formatHex(bytes));
        }

        PlayerSnapshotDTO player = new PlayerSnapshotDTO();
        player.roleId = 42;
        player.roleName = "Zoe";
        player.level = 7;
        player.exp = 123456789L;
        player.revision = 9876543210L;
        PlayerCurrencyDTO currency = new PlayerCurrencyDTO();
        currency.type = CurrencyType.GOLD;
        currency.amount = -5;
        player.currencies.add(currency);
        player.counters.put("wins", 2L);
        player.guildName = "Guild";
        PlayerPlayerSnapshotProtocolDTO snapshot = new PlayerPlayerSnapshotProtocolDTO();
        snapshot.player = player;
        snapshot.serverTimeMillis = 1700000000000L;
        try (ZeroWriter writer = new ZeroWriter()) {
            PlayerPlayerSnapshotProtocolDTOCodec.INSTANCE.write(writer, snapshot);
            byte[] bytes = writer.toByteArray();
            PlayerPlayerSnapshotProtocolDTO read =
                    PlayerPlayerSnapshotProtocolDTOCodec.INSTANCE.read(new ZeroReader(bytes));
            if (read.player.roleId != player.roleId || read.player.revision != player.revision
                    || read.player.currencies.get(0).amount != -5
                    || read.player.counters.get("wins") != 2
                    || !"Guild".equals(read.player.guildName)
                    || read.serverTimeMillis != snapshot.serverTimeMillis) {
                throw new AssertionError("snapshot round trip failed");
            }
            System.out.println("snapshot=" + HexFormat.of().formatHex(bytes));
        }
    }

    private static void boundaries() {
        try (ZeroWriter writer = new ZeroWriter()) {
            writer.writeByte(-128);
            writer.writeShort((short) -32768);
            writer.writeInt(Integer.MIN_VALUE);
            writer.writeUnsignedInt(Integer.MAX_VALUE);
            writer.writeLong(Long.MIN_VALUE);
            writer.writeUnsignedLong(Long.MAX_VALUE);
            ZeroReader reader = new ZeroReader(writer.toByteArray());
            if (reader.readByte() != -128 || reader.readShort() != -32768
                    || reader.readInt() != Integer.MIN_VALUE || reader.readUnsignedInt() != Integer.MAX_VALUE
                    || reader.readLong() != Long.MIN_VALUE || reader.readUnsignedLong() != Long.MAX_VALUE) {
                throw new AssertionError("scalar boundary failed");
            }
            rejects(() -> writer.writeUnsignedLong(-1));
            System.out.println("boundary=" + HexFormat.of().formatHex(writer.toByteArray()));
        }
        rejects(() -> new ZeroReader(HexFormat.of().parseHex("ffffffffffffffffff01")).readUnsignedLong());
        rejects(() -> new ZeroReader(HexFormat.of().parseHex("ffffffff07")).readList(ZeroReader::readByte));
        rejects(() -> new ZeroReader(HexFormat.of().parseHex("ffffffff07")).readPresenceBits());
        rejects(() -> new ZeroReader(HexFormat.of().parseHex("808004")).readShort());
        rejects(() -> { ZeroReader r = new ZeroReader(new byte[]{1, (byte) 128, 1});
            r.beginObject(); r.readInt(); });
    }

    private static void rejects(final Runnable action) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("invalid payload was accepted");
    }

    private static void generatedLimits() {
        WireLimitsDTO limits = new WireLimitsDTO();
        limits.lowByte = -128;
        limits.lowShort = -32768;
        limits.highUint = Integer.MAX_VALUE;
        limits.lowLong = Long.MIN_VALUE;
        limits.highUlong = Long.MAX_VALUE;
        limits.data = new byte[]{0, (byte) 255};
        limits.signedBytes = new byte[]{-128, 127};
        try (ZeroWriter writer = new ZeroWriter()) {
            WireLimitsDTOCodec.INSTANCE.write(writer, limits);
            WireLimitsDTO read = WireLimitsDTOCodec.INSTANCE.read(new ZeroReader(writer.toByteArray()));
            if (read.lowByte != -128 || read.lowShort != -32768 || read.highUint != Integer.MAX_VALUE
                    || read.lowLong != Long.MIN_VALUE || read.highUlong != Long.MAX_VALUE
                    || read.data[1] != (byte) 255 || read.signedBytes[0] != -128) {
                throw new AssertionError("generated scalar DTO failed");
            }
        }
    }
}
