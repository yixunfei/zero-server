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

void Rejects(Action action) {
    bool failed = false;
    try { action(); } catch (Exception) { failed = true; }
    if (!failed) throw new Exception("invalid payload was accepted");
}
var boundary = new ZeroWriter();
boundary.WriteByte(-128);
boundary.WriteShort(-32768);
boundary.WriteInt(int.MinValue);
boundary.WriteUnsignedInt(int.MaxValue);
boundary.WriteLong(long.MinValue);
boundary.WriteUnsignedLong(long.MaxValue);
var reader = new ZeroReader(boundary.ToArray());
if (reader.ReadByte() != -128 || reader.ReadShort() != -32768 || reader.ReadInt() != int.MinValue
    || reader.ReadUnsignedInt() != int.MaxValue || reader.ReadLong() != long.MinValue
    || reader.ReadUnsignedLong() != long.MaxValue) throw new Exception("scalar boundary failed");
Rejects(() => new ZeroWriter().WriteUnsignedLong(1UL << 63));
Rejects(() => new ZeroWriter().WriteByte(128));
Rejects(() => new ZeroReader(new byte[]{255,255,255,255,255,255,255,255,255,1}).ReadUnsignedLong());
Rejects(() => new ZeroReader(new byte[]{255,255,255,255,7}).ReadArray(r => r.ReadByte()));
Rejects(() => new ZeroReader(new byte[]{255,255,255,255,7}).ReadPresenceBits());
Rejects(() => new ZeroReader(new byte[]{128,128,4}).ReadShort());
Rejects(() => { var r = new ZeroReader(new byte[]{1,128,1}); r.BeginObject(); r.ReadInt(); });
Console.WriteLine("boundary=" + Convert.ToHexString(boundary.ToArray()).ToLowerInvariant());

var limits = new WireLimitsDTO { lowByte = -128, lowShort = -32768, highUint = int.MaxValue,
    lowLong = long.MinValue, highUlong = long.MaxValue, data = new byte[]{0, 255}, signedBytes = new sbyte[]{-128, 127} };
var limitsWriter = new ZeroWriter();
WireLimitsDTOCodec.Write(limitsWriter, limits);
var limitsRead = WireLimitsDTOCodec.Read(new ZeroReader(limitsWriter.ToArray()));
if (limitsRead.lowByte != -128 || limitsRead.lowShort != -32768 || limitsRead.highUint != int.MaxValue
    || limitsRead.lowLong != long.MinValue || limitsRead.highUlong != long.MaxValue
    || limitsRead.data[1] != 255 || limitsRead.signedBytes[0] != -128) throw new Exception("generated scalar DTO failed");
