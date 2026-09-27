import { ZeroReader, ZeroWriter } from './zero-protocol-runtime';
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
