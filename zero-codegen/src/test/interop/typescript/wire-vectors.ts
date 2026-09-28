import { ZeroReader, ZeroWriter } from './zero-protocol-runtime';
import { WireLimitsDTO } from './wire-limits-dto';
import { WireLimitsDTOCodec } from './wire-limits-dto-codec';
import { CurrencyType } from './currency-type';
import { PlayerCurrencyDTO } from './player-currency-dto';
import { PlayerPlayerSnapshotProtocolDTO } from './player-player-snapshot-protocol-dto';
import { PlayerPlayerSnapshotProtocolDTOCodec } from './player-player-snapshot-protocol-dto-codec';
import { PlayerSnapshotDTO } from './player-snapshot-dto';
import { RoleClass } from './role-class';
import { RoleCreateRoleProtocolDTO } from './role-create-role-protocol-dto';
import { RoleCreateRoleProtocolDTOCodec } from './role-create-role-protocol-dto-codec';

function hex(bytes: Uint8Array): string {
  return Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('');
}

const role = new RoleCreateRoleProtocolDTO();
role.uid = -1234567890123n;
role.roleName = 'Zoe';
role.roleClass = RoleClass.MAGE;
role.traceId = 'trace-42';
const roleWriter = new ZeroWriter();
RoleCreateRoleProtocolDTOCodec.write(roleWriter, role);
const roleBytes = roleWriter.toUint8Array();
const roleRead = RoleCreateRoleProtocolDTOCodec.read(new ZeroReader(roleBytes));
if (roleRead.uid !== role.uid || roleRead.roleName !== role.roleName
    || roleRead.roleClass !== role.roleClass || roleRead.traceId !== role.traceId) {
  throw new Error('role round trip failed');
}
console.log('role=' + hex(roleBytes));

const player = new PlayerSnapshotDTO();
player.roleId = 42n;
player.roleName = 'Zoe';
player.level = 7;
player.exp = 123456789n;
player.revision = 9876543210n;
const currency = new PlayerCurrencyDTO();
currency.type = CurrencyType.GOLD;
currency.amount = -5n;
player.currencies.push(currency);
player.counters.set('wins', 2n);
player.guildName = 'Guild';
const snapshot = new PlayerPlayerSnapshotProtocolDTO();
snapshot.player = player;
snapshot.serverTimeMillis = 1700000000000n;
const snapshotWriter = new ZeroWriter();
PlayerPlayerSnapshotProtocolDTOCodec.write(snapshotWriter, snapshot);
const snapshotBytes = snapshotWriter.toUint8Array();
const snapshotRead = PlayerPlayerSnapshotProtocolDTOCodec.read(new ZeroReader(snapshotBytes));
if (snapshotRead.player.roleId !== player.roleId || snapshotRead.player.revision !== player.revision
    || snapshotRead.player.currencies[0].amount !== -5n
    || snapshotRead.player.counters.get('wins') !== 2n
    || snapshotRead.player.guildName !== 'Guild'
    || snapshotRead.serverTimeMillis !== snapshot.serverTimeMillis) {
  throw new Error('snapshot round trip failed');
}
console.log('snapshot=' + hex(snapshotBytes));

function rejects(action: () => unknown): void {
  let failed = false;
  try { action(); } catch { failed = true; }
  if (!failed) throw new Error('invalid payload was accepted');
}

const boundary = new ZeroWriter();
boundary.writeByte(-128);
boundary.writeShort(-32768);
boundary.writeInt(-2147483648);
boundary.writeUnsignedInt(2147483647);
boundary.writeLong(-(1n << 63n));
boundary.writeUnsignedLong((1n << 63n) - 1n);
const reader = new ZeroReader(boundary.toUint8Array());
if (reader.readByte() !== -128 || reader.readShort() !== -32768 || reader.readInt() !== -2147483648
    || reader.readUnsignedInt() !== 2147483647 || reader.readLong() !== -(1n << 63n)
    || reader.readUnsignedLong() !== (1n << 63n) - 1n) throw new Error('scalar boundary failed');
rejects(() => new ZeroWriter().writeUnsignedLong(1n << 63n));
rejects(() => new ZeroWriter().writeLong(1n << 64n));
rejects(() => new ZeroWriter().writeInt(1.5));
rejects(() => new ZeroWriter().writeByte(128));
rejects(() => new ZeroWriter().writeUnsignedInt(2147483648));
rejects(() => new ZeroReader(Uint8Array.of(255,255,255,255,255,255,255,255,255,1)).readUnsignedLong());
rejects(() => new ZeroReader(Uint8Array.of(255,255,255,255,7)).readArray(r => r.readByte()));
rejects(() => new ZeroReader(Uint8Array.of(255,255,255,255,7)).readPresenceBits());
rejects(() => new ZeroReader(Uint8Array.of(128,128,4)).readShort());
rejects(() => { const r = new ZeroReader(Uint8Array.of(1,128,1)); r.beginObject(); r.readInt(); });
console.log('boundary=' + hex(boundary.toUint8Array()));

const limits = new WireLimitsDTO();
limits.lowByte = -128; limits.lowShort = -32768; limits.highUint = 2147483647;
limits.lowLong = -(1n << 63n); limits.highUlong = (1n << 63n) - 1n;
limits.data = Uint8Array.of(0, 255); limits.signedBytes = [-128, 127];
const limitsWriter = new ZeroWriter();
WireLimitsDTOCodec.write(limitsWriter, limits);
const limitsRead = WireLimitsDTOCodec.read(new ZeroReader(limitsWriter.toUint8Array()));
if (limitsRead.lowByte !== -128 || limitsRead.lowShort !== -32768 || limitsRead.highUint !== 2147483647
    || limitsRead.lowLong !== limits.lowLong || limitsRead.highUlong !== limits.highUlong
    || limitsRead.data[1] !== 255 || limitsRead.signedBytes[0] !== -128) throw new Error('generated scalar DTO failed');
