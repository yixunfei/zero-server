extends SceneTree

const Protocol = preload("res://zero_protocol.gd")

func _initialize() -> void:
    var role = Protocol.RoleCreateRoleProtocolDTO.new()
    role.uid = -1234567890123
    role.roleName = "Zoe"
    role.roleClass = Protocol.RoleClass.MAGE
    role.traceId = "trace-42"
    var role_writer = Protocol.ZeroWriter.new()
    Protocol.RoleCreateRoleProtocolDTOCodec.write(role_writer, role)
    var role_bytes = role_writer.to_byte_array()
    var role_read = Protocol.RoleCreateRoleProtocolDTOCodec.read(Protocol.ZeroReader.new(role_bytes))
    assert(role_read.uid == role.uid and role_read.roleName == role.roleName)
    assert(role_read.roleClass == role.roleClass and role_read.traceId == role.traceId)
    print("role=" + role_bytes.hex_encode())

    var player = Protocol.PlayerSnapshotDTO.new()
    player.roleId = 42
    player.roleName = "Zoe"
    player.level = 7
    player.exp = 123456789
    player.revision = 9876543210
    var currency = Protocol.PlayerCurrencyDTO.new()
    currency.type = Protocol.CurrencyType.GOLD
    currency.amount = -5
    player.currencies.append(currency)
    player.counters["wins"] = 2
    player.guildName = "Guild"
    var snapshot = Protocol.PlayerPlayerSnapshotProtocolDTO.new()
    snapshot.player = player
    snapshot.serverTimeMillis = 1700000000000
    var snapshot_writer = Protocol.ZeroWriter.new()
    Protocol.PlayerPlayerSnapshotProtocolDTOCodec.write(snapshot_writer, snapshot)
    var snapshot_bytes = snapshot_writer.to_byte_array()
    var snapshot_read = Protocol.PlayerPlayerSnapshotProtocolDTOCodec.read(
            Protocol.ZeroReader.new(snapshot_bytes))
    assert(snapshot_read.player.roleId == player.roleId)
    assert(snapshot_read.player.revision == player.revision)
    assert(snapshot_read.player.currencies[0].amount == -5)
    assert(snapshot_read.player.counters["wins"] == 2)
    assert(snapshot_read.player.guildName == "Guild")
    assert(snapshot_read.serverTimeMillis == snapshot.serverTimeMillis)
    print("snapshot=" + snapshot_bytes.hex_encode())
    quit()
