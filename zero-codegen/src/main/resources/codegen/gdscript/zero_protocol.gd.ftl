# ${generatedMarker}. Do not edit manually.
extends RefCounted

const NAMESPACE := "${namespace}"

class ProtocolIds:
<#list protocolIds as item>
    # ${item.comment}
    const ${item.constantName} := ${item.id?c}
</#list>

<#list enums as enum>
enum ${enum.name} {
<#list enum.values as value>
    ${value.name} = ${value.value?c}<#if value_has_next>,</#if>
</#list>
}

</#list>
class ZeroWriter:
    const MAX_INT_BYTES := 5
    const LOGICAL_SHIFT_7_MASK_64 := 0x01FFFFFFFFFFFFFF

    var buffer: PackedByteArray = PackedByteArray()
    var error_message: String = ""

    func is_valid() -> bool:
        return error_message.is_empty()

    func get_error() -> String:
        return error_message

    func _fail(message: String) -> void:
        if error_message.is_empty():
            error_message = message

    func to_byte_array() -> PackedByteArray:
        return buffer.duplicate() if is_valid() else PackedByteArray()

    func write_boolean(value: bool) -> void:
        _write_raw_byte(1 if value else 0)

    func write_byte(value: int) -> void:
        if value < -128 or value > 127:
            _fail("byte exceeds signed byte range")
            return
        _write_raw_byte(value)

    func _write_raw_byte(value: int) -> void:
        if is_valid():
            buffer.append(value & 0xff)

    func write_short(value: int) -> void:
        if value < -32768 or value > 32767:
            _fail("short exceeds signed short range")
            return
        write_int(value)

    func write_int(value: int) -> void:
        if value < -2147483648 or value > 2147483647:
            _fail("int exceeds signed int range")
            return
        write_raw_varint32(((value << 1) ^ (value >> 31)) & 0xffffffff)

    func write_unsigned_int(value: int) -> void:
        if value < 0 or value > 0x7fffffff:
            _fail("unsigned int exceeds positive int range")
            return
        write_raw_varint32(value)

    func write_long(value: int) -> void:
        write_raw_varint64((value << 1) ^ (value >> 63))

    func write_unsigned_long(value: int) -> void:
        if value < 0 or value > 0x7fffffffffffffff:
            _fail("unsigned long exceeds positive long range")
            return
        write_raw_varint64(value)

    func write_float(value: float) -> void:
        var peer := StreamPeerBuffer.new()
        peer.big_endian = true
        peer.put_float(value)
        write_raw_bytes(peer.data_array)

    func write_double(value: float) -> void:
        var peer := StreamPeerBuffer.new()
        peer.big_endian = true
        peer.put_double(value)
        write_raw_bytes(peer.data_array)

    func write_string(value: String) -> void:
        write_byte_array(value.to_utf8_buffer())

    func write_byte_array(value: PackedByteArray) -> void:
        if value.is_empty():
            write_unsigned_int(0)
            return
        write_unsigned_int(value.size())
        write_raw_bytes(value)

    func write_array(values: Array, item_writer: Callable) -> void:
        write_unsigned_int(values.size())
        for value in values:
            if not is_valid():
                return
            item_writer.call(self, value)

    func write_map(values: Dictionary, key_writer: Callable, value_writer: Callable) -> void:
        write_unsigned_int(values.size())
        for key in values.keys():
            if not is_valid():
                return
            key_writer.call(self, key)
            value_writer.call(self, values[key])

    func write_presence_bits(field_count: int, present_predicate: Callable) -> void:
        write_unsigned_int(field_count)
        if not is_valid():
            return
        var byte_count := int((field_count + 7) / 8)
        for byte_index in range(byte_count):
            var word := 0
            var base := byte_index * 8
            var end: int = min(field_count, base + 8)
            for bit_index in range(base, end):
                if present_predicate.call(bit_index):
                    word |= 1 << (bit_index - base)
            _write_raw_byte(word)

    func begin_object() -> int:
        if not is_valid():
            return -1
        var marker := buffer.size()
        for _i in range(MAX_INT_BYTES):
            buffer.append(0)
        return marker

    func end_object(marker: int) -> void:
        if not is_valid():
            return
        if marker < 0 or marker + MAX_INT_BYTES > buffer.size():
            _fail("invalid object marker")
            return
        var content_start := marker + MAX_INT_BYTES
        var length := buffer.size() - content_start
        var encoded := _encode_unsigned_int(length)
        var new_buffer := buffer.slice(0, marker)
        new_buffer.append_array(encoded)
        new_buffer.append_array(buffer.slice(content_start))
        buffer = new_buffer

    func write_raw_bytes(bytes: PackedByteArray) -> void:
        if is_valid():
            buffer.append_array(bytes)

    func write_raw_varint32(value: int) -> void:
        if not is_valid():
            return
        if value < 0 or value > 0xffffffff:
            _fail("raw varint32 exceeds 32-bit range")
            return
        var remaining := value
        while (remaining & ~0x7f) != 0:
            _write_raw_byte((remaining & 0x7f) | 0x80)
            remaining = remaining >> 7
        _write_raw_byte(remaining)

    func write_raw_varint64(value: int) -> void:
        if not is_valid():
            return
        var remaining := value
        while (remaining & ~0x7f) != 0:
            _write_raw_byte((remaining & 0x7f) | 0x80)
            remaining = int((remaining >> 7) & LOGICAL_SHIFT_7_MASK_64)
        _write_raw_byte(remaining)

    func _encode_unsigned_int(value: int) -> PackedByteArray:
        var result := PackedByteArray()
        var remaining := value
        while (remaining & ~0x7f) != 0:
            result.append((remaining & 0x7f) | 0x80)
            remaining = remaining >> 7
        result.append(remaining)
        return result


class ZeroReader:
    var buffer: PackedByteArray
    var reader_index: int
    var limit: int
    var error_message: String = ""
    var object_limits: Array[int] = []

    func _init(bytes: PackedByteArray, offset: int = 0, length: int = -1) -> void:
        if offset < 0 or offset > bytes.size() or length < -1 or (length >= 0 and length > bytes.size() - offset):
            _fail("invalid reader range")
            return
        buffer = bytes
        reader_index = offset
        limit = bytes.size() if length < 0 else offset + length

    func is_valid() -> bool:
        return error_message.is_empty()

    func get_error() -> String:
        return error_message

    func _fail(message: String) -> void:
        if error_message.is_empty():
            error_message = message

    func read_boolean() -> bool:
        return _read_raw_byte() != 0

    func read_byte() -> int:
        var value := _read_raw_byte()
        return value - 256 if value > 127 else value

    func _read_raw_byte() -> int:
        if not _require_readable(1):
            return 0
        var value: int = buffer[reader_index]
        reader_index += 1
        return value

    func read_short() -> int:
        var value := read_int()
        if value < -32768 or value > 32767:
            _fail("short exceeds signed short range")
            return 0
        return value

    func read_int() -> int:
        var raw := read_raw_varint32()
        return (raw >> 1) ^ -(raw & 1)

    func read_unsigned_int() -> int:
        var value := read_raw_varint32()
        if value < 0 or value > 0x7fffffff:
            _fail("unsigned int exceeds positive int range")
        return value

    func read_long() -> int:
        var raw := read_raw_varint64()
        return ((raw >> 1) & 0x7FFFFFFFFFFFFFFF) ^ -(raw & 1)

    func read_unsigned_long() -> int:
        var value := read_raw_varint64()
        if value < 0 or value > 0x7fffffffffffffff:
            _fail("unsigned long exceeds positive long range")
        return value

    func read_float() -> float:
        if not _require_readable(4):
            return 0.0
        var bytes := buffer.slice(reader_index, reader_index + 4)
        reader_index += 4
        var peer := StreamPeerBuffer.new()
        peer.big_endian = true
        peer.data_array = bytes
        return peer.get_float()

    func read_double() -> float:
        if not _require_readable(8):
            return 0.0
        var bytes := buffer.slice(reader_index, reader_index + 8)
        reader_index += 8
        var peer := StreamPeerBuffer.new()
        peer.big_endian = true
        peer.data_array = bytes
        return peer.get_double()

    func read_string() -> String:
        var length := read_unsigned_int()
        if length == 0:
            return ""
        if not _require_readable(length):
            return ""
        var bytes := buffer.slice(reader_index, reader_index + length)
        reader_index += length
        return bytes.get_string_from_utf8()

    func read_byte_array() -> PackedByteArray:
        var length := read_unsigned_int()
        if length == 0:
            return PackedByteArray()
        if not _require_readable(length):
            return PackedByteArray()
        var value := buffer.slice(reader_index, reader_index + length)
        reader_index += length
        return value

    func read_array(item_reader: Callable) -> Array:
        var count := read_unsigned_int()
        if not _require_readable(count):
            return []
        var values := []
        for _i in range(count):
            values.append(item_reader.call(self))
            if not is_valid():
                return []
        return values

    func read_map(key_reader: Callable, value_reader: Callable) -> Dictionary:
        var count := read_unsigned_int()
        if not _require_readable(count * 2):
            return {}
        var values := {}
        for _i in range(count):
            var key = key_reader.call(self)
            values[key] = value_reader.call(self)
            if not is_valid():
                return {}
        return values

    func begin_object() -> int:
        var length := read_unsigned_int()
        if not is_valid():
            return -1
        var object_end := reader_index + length
        if object_end < reader_index or object_end > limit:
            _fail("object length exceeds readable bytes")
            return -1
        object_limits.push_back(limit)
        limit = object_end
        return object_end

    func has_remaining_in_object(object_end: int) -> bool:
        if not is_valid():
            return false
        if object_end < reader_index or object_end != limit or object_limits.is_empty():
            _fail("invalid object end index")
            return false
        return reader_index < object_end

    func end_object(object_end: int) -> void:
        if not is_valid():
            return
        if object_end < reader_index or object_end != limit or object_limits.is_empty():
            _fail("invalid object end index")
            return
        reader_index = object_end
        limit = object_limits.pop_back()

    func read_presence_bits() -> Array:
        var field_count := read_unsigned_int()
        var byte_count := int((field_count + 7) / 8)
        if not _require_readable(byte_count):
            return []
        var values := []
        values.resize(field_count)
        for byte_index in range(byte_count):
            var word := _read_raw_byte()
            var base := byte_index * 8
            var end: int = min(field_count, base + 8)
            for bit_index in range(base, end):
                values[bit_index] = ((word >> (bit_index - base)) & 1) != 0
        return values

    func read_raw_varint32() -> int:
        var shift := 0
        var result := 0
        for index in range(5):
            var value := _read_raw_byte()
            if not is_valid():
                return 0
            result |= (value & 0x7f) << shift
            if (value & 0x80) == 0:
                if index == 4 and (value & 0xf0) != 0:
                    _fail("unsigned int varint overflow")
                    return 0
                return result
            shift += 7
        _fail("unsigned int varint is too long")
        return 0

    func read_raw_varint64() -> int:
        var shift := 0
        var result := 0
        for index in range(10):
            var value := _read_raw_byte()
            if not is_valid():
                return 0
            result |= (value & 0x7f) << shift
            if (value & 0x80) == 0:
                if index == 9 and (value & 0xfe) != 0:
                    _fail("unsigned long varint overflow")
                    return 0
                return result
            shift += 7
        _fail("unsigned long varint is too long")
        return 0

    func _require_readable(length: int) -> bool:
        if not is_valid():
            return false
        if length < 0 or length > limit - reader_index:
            _fail("not enough readable bytes")
            return false
        return true


class ZeroGeneratedPayload:
    pass


<#list messages as message>
class ${message.name} extends ZeroGeneratedPayload:
<#if message.fields?size == 0>
    pass
<#else>
<#list message.fields as field>
    # ${field.comment}
    var ${field.name} = ${field.defaultValue}
</#list>
</#if>


class ${message.name}Codec:
    static func write(writer: ZeroWriter, message: ${message.name}) -> void:
        if not writer.is_valid():
            return
        if message == null:
            writer._fail("required message is null")
            return
        var object_marker := writer.begin_object()
<#if message.hasNullableFields>
        writer.write_presence_bits(${message.nullableFieldCount?c}, func(index):
            match index:
<#list message.writeFields as field>
<#if field.nullable>
                ${field.presenceIndex?c}:
                    return ${field.presentExpression}
</#if>
</#list>
                _:
                    return false
        )
</#if>
<#list message.writeFields as field>
<#if field.nullable>
        if ${field.presentExpression}:
${field.writeCode}<#else>
${field.writeCode}</#if>
</#list>
        writer.end_object(object_marker)

    static func read(reader: ZeroReader) -> ${message.name}:
        var object_end := reader.begin_object()
        var message := ${message.name}.new()
<#if message.hasNullableFields>
        var presence := reader.read_presence_bits() if reader.has_remaining_in_object(object_end) else []
</#if>
<#list message.readFields as field>
<#if field.nullable>
        if ${field.presenceIndex?c} < presence.size() and presence[${field.presenceIndex?c}] and reader.has_remaining_in_object(object_end):
            message.${field.name} = ${field.readExpression}
<#else>
        if reader.has_remaining_in_object(object_end):
            message.${field.name} = ${field.readExpression}
</#if>
</#list>
        reader.end_object(object_end)
        return message if reader.is_valid() else null


</#list>
