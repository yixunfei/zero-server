import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.standard.dto.CurrencyType;
import group.zn.zero.standard.dto.PlayerCurrencyDTO;
import group.zn.zero.standard.dto.PlayerPlayerSnapshotProtocolDTO;
import group.zn.zero.standard.dto.PlayerSnapshotDTO;
import group.zn.zero.standard.dto.RoleClass;
import group.zn.zero.standard.dto.RoleCreateRoleProtocolDTO;
import group.zn.zero.standard.dto.codec.PlayerPlayerSnapshotProtocolDTOCodec;
import group.zn.zero.standard.dto.codec.RoleCreateRoleProtocolDTOCodec;
import java.util.HexFormat;

/** Compiled against freshly generated server sources by smoke-interop.ps1. */
public final class WireVectors {
    private WireVectors() {
    }

    public static void main(final String[] args) {
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
}
