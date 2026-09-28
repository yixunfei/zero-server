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
    boundaries()
    generated_limits()
    quit()

func boundaries() -> void:
    var writer = Protocol.ZeroWriter.new()
    writer.write_byte(-128)
    writer.write_short(-32768)
    writer.write_int(-2147483648)
    writer.write_unsigned_int(2147483647)
    writer.write_long(-9223372036854775807 - 1)
    writer.write_unsigned_long(9223372036854775807)
    assert(writer.is_valid())
    var reader = Protocol.ZeroReader.new(writer.to_byte_array())
    assert(reader.read_byte() == -128)
    assert(reader.read_short() == -32768)
    assert(reader.read_int() == -2147483648)
    assert(reader.read_unsigned_int() == 2147483647)
    assert(reader.read_long() == -9223372036854775807 - 1)
    assert(reader.read_unsigned_long() == 9223372036854775807)
    assert(reader.is_valid())
    print("boundary=" + writer.to_byte_array().hex_encode())
    writer.write_unsigned_int(2147483648)
    assert(not writer.is_valid() and writer.to_byte_array().is_empty())
    var raw_writer = Protocol.ZeroWriter.new()
    raw_writer.write_raw_varint32(-1)
    assert(not raw_writer.is_valid())
    for bytes in ["ffffffffffffffffff01", "80"]:
        reader = Protocol.ZeroReader.new(bytes.hex_decode())
        reader.read_unsigned_long()
        assert(not reader.is_valid())
    reader = Protocol.ZeroReader.new("ffffffff07".hex_decode())
    assert(reader.read_array(func(r): return r.read_byte()).is_empty())
    assert(not reader.is_valid())
    reader = Protocol.ZeroReader.new("ffffffff07".hex_decode())
    assert(reader.read_presence_bits().is_empty() and not reader.is_valid())
    reader = Protocol.ZeroReader.new("808004".hex_decode())
    reader.read_short()
    assert(not reader.is_valid())
    reader = Protocol.ZeroReader.new("018001".hex_decode())
    reader.begin_object()
    reader.read_int()
    assert(not reader.is_valid())
    reader = Protocol.ZeroReader.new("0480".hex_decode())
    assert(Protocol.RoleCreateRoleProtocolDTOCodec.read(reader) == null)
    assert(not reader.is_valid())

func generated_limits() -> void:
    var limits = Protocol.WireLimitsDTO.new()
    limits.lowByte = -128
    limits.lowShort = -32768
    limits.highUint = 2147483647
    limits.lowLong = -9223372036854775807 - 1
    limits.highUlong = 9223372036854775807
    limits.data = PackedByteArray([0, 255])
    limits.signedBytes = [-128, 127]
    var writer = Protocol.ZeroWriter.new()
    Protocol.WireLimitsDTOCodec.write(writer, limits)
    assert(writer.is_valid())
    var read = Protocol.WireLimitsDTOCodec.read(Protocol.ZeroReader.new(writer.to_byte_array()))
    assert(read.lowByte == -128 and read.lowShort == -32768 and read.highUint == 2147483647)
    assert(read.lowLong == limits.lowLong and read.highUlong == limits.highUlong)
    assert(read.data[1] == 255 and read.signedBytes[0] == -128)
