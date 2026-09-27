using System;
using Group.Zn.Zero.Standard;

var role = new RoleCreateRoleProtocolDTO {
    uid = -1234567890123L, roleName = "Zoe", roleClass = RoleClass.MAGE, traceId = "trace-42"
};
var roleWriter = new ZeroWriter();
RoleCreateRoleProtocolDTOCodec.Write(roleWriter, role);
byte[] roleBytes = roleWriter.ToArray();
var roleRead = RoleCreateRoleProtocolDTOCodec.Read(new ZeroReader(roleBytes));
if (roleRead.uid != role.uid || roleRead.roleName != role.roleName
    || roleRead.roleClass != role.roleClass || roleRead.traceId != role.traceId) {
    throw new Exception("role round trip failed");
}
Console.WriteLine("role=" + Convert.ToHexString(roleBytes).ToLowerInvariant());

var player = new PlayerSnapshotDTO {
    roleId = 42, roleName = "Zoe", level = 7, exp = 123456789L,
    revision = 9876543210UL, guildName = "Guild"
};
player.currencies.Add(new PlayerCurrencyDTO { type = CurrencyType.GOLD, amount = -5 });
player.counters.Add("wins", 2L);
var snapshot = new PlayerPlayerSnapshotProtocolDTO {
    player = player, serverTimeMillis = 1700000000000L
};
var snapshotWriter = new ZeroWriter();
PlayerPlayerSnapshotProtocolDTOCodec.Write(snapshotWriter, snapshot);
byte[] snapshotBytes = snapshotWriter.ToArray();
var snapshotRead = PlayerPlayerSnapshotProtocolDTOCodec.Read(new ZeroReader(snapshotBytes));
if (snapshotRead.player.roleId != player.roleId || snapshotRead.player.revision != player.revision
    || snapshotRead.player.currencies[0].amount != -5
    || snapshotRead.player.counters["wins"] != 2
    || snapshotRead.player.guildName != "Guild"
    || snapshotRead.serverTimeMillis != snapshot.serverTimeMillis) {
    throw new Exception("snapshot round trip failed");
}
Console.WriteLine("snapshot=" + Convert.ToHexString(snapshotBytes).ToLowerInvariant());
