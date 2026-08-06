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

    func to_byte_array() -> PackedByteArray:
        return buffer.duplicate()

    func write_boolean(value: bool) -> void:
        write_byte(1 if value else 0)

    func write_byte(value: int) -> void:
        buffer.append(value & 0xff)

    func write_short(value: int) -> void:
        write_int(value)

    func write_int(value: int) -> void:
        write_raw_varint32(((value << 1) ^ (value >> 31)) & 0xffffffff)

    func write_unsigned_int(value: int) -> void:
        if value < 0:
            push_error("unsigned int must not be negative")
            return
        write_raw_varint32(value)

    func write_long(value: int) -> void:
        write_raw_varint64((value << 1) ^ (value >> 63))

    func write_unsigned_long(value: int) -> void:
        if value < 0:
            push_error("unsigned long must not be negative")
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
            item_writer.call(self, value)

    func write_map(values: Dictionary, key_writer: Callable, value_writer: Callable) -> void:
        write_unsigned_int(values.size())
        for key in values.keys():
            key_writer.call(self, key)
            value_writer.call(self, values[key])

    func write_presence_bits(field_count: int, present_predicate: Callable) -> void:
        write_unsigned_int(field_count)
        var byte_count := int((field_count + 7) / 8)
        for byte_index in range(byte_count):
            var word := 0
            var base := byte_index * 8
            var end := min(field_count, base + 8)
            for bit_index in range(base, end):
                if present_predicate.call(bit_index):
                    word |= 1 << (bit_index - base)
            write_byte(word)

    func begin_object() -> int:
        var marker := buffer.size()
        for _i in range(MAX_INT_BYTES):
            buffer.append(0)
        return marker

    func end_object(marker: int) -> void:
        if marker < 0 or marker + MAX_INT_BYTES > buffer.size():
            push_error("invalid object marker")
            return
        var content_start := marker + MAX_INT_BYTES
        var length := buffer.size() - content_start
        var encoded := _encode_unsigned_int(length)
        var new_buffer := PackedByteArray()
        for index in range(marker):
            new_buffer.append(buffer[index])
        new_buffer.append_array(encoded)
        for index in range(content_start, buffer.size()):
            new_buffer.append(buffer[index])
        buffer = new_buffer

    func write_raw_bytes(bytes: PackedByteArray) -> void:
        buffer.append_array(bytes)

    func write_raw_varint32(value: int) -> void:
        var remaining := value
        while (remaining & ~0x7f) != 0:
            write_byte((remaining & 0x7f) | 0x80)
            remaining = remaining >> 7
        write_byte(remaining)

    func write_raw_varint64(value: int) -> void:
        var remaining := value
        while (remaining & ~0x7f) != 0:
            write_byte((remaining & 0x7f) | 0x80)
            remaining = int((remaining >> 7) & LOGICAL_SHIFT_7_MASK_64)
        write_byte(remaining)

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

    func _init(bytes: PackedByteArray, offset: int = 0, length: int = -1) -> void:
        buffer = bytes
        reader_index = offset
        limit = bytes.size() if length < 0 else offset + length

    func read_boolean() -> bool:
        return read_byte() != 0

    func read_byte() -> int:
        _require_readable(1)
        var value := buffer[reader_index]
        reader_index += 1
        return value

    func read_short() -> int:
        return read_int()

    func read_int() -> int:
        var raw := read_raw_varint32()
        return (raw >> 1) ^ -(raw & 1)

    func read_unsigned_int() -> int:
        var value := read_raw_varint32()
        if value < 0:
            push_error("unsigned int exceeds positive int range")
        return value

    func read_long() -> int:
        var raw := read_raw_varint64()
        return ((raw >> 1) & 0x7FFFFFFFFFFFFFFF) ^ -(raw & 1)

    func read_unsigned_long() -> int:
        var value := read_raw_varint64()
        if value < 0:
            push_error("unsigned long exceeds positive long range")
        return value

    func read_float() -> float:
        _require_readable(4)
        var bytes := buffer.slice(reader_index, reader_index + 4)
        reader_index += 4
        var peer := StreamPeerBuffer.new()
        peer.big_endian = true
        peer.data_array = bytes
        return peer.get_float()

    func read_double() -> float:
        _require_readable(8)
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
        _require_readable(length)
        var bytes := buffer.slice(reader_index, reader_index + length)
        reader_index += length
        return bytes.get_string_from_utf8()

    func read_byte_array() -> PackedByteArray:
        var length := read_unsigned_int()
        if length == 0:
            return PackedByteArray()
        _require_readable(length)
        var value := buffer.slice(reader_index, reader_index + length)
        reader_index += length
        return value

    func read_array(item_reader: Callable) -> Array:
        var count := read_unsigned_int()
        var values := []
        for _i in range(count):
            values.append(item_reader.call(self))
        return values

    func read_map(key_reader: Callable, value_reader: Callable) -> Dictionary:
        var count := read_unsigned_int()
        var values := {}
        for _i in range(count):
            var key = key_reader.call(self)
            values[key] = value_reader.call(self)
        return values

    func begin_object() -> int:
        var length := read_unsigned_int()
        var object_end := reader_index + length
        if object_end < reader_index or object_end > limit:
            push_error("object length exceeds readable bytes")
        return object_end

    func has_remaining_in_object(object_end: int) -> bool:
        if object_end < reader_index or object_end > limit:
            push_error("invalid object end index")
        return reader_index < object_end

    func end_object(object_end: int) -> void:
        if object_end < reader_index or object_end > limit:
            push_error("invalid object end index")
        reader_index = object_end

    func read_presence_bits() -> Array:
        var field_count := read_unsigned_int()
        var values := []
        values.resize(field_count)
        var byte_count := int((field_count + 7) / 8)
        for byte_index in range(byte_count):
            var word := read_byte()
            var base := byte_index * 8
            var end := min(field_count, base + 8)
            for bit_index in range(base, end):
                values[bit_index] = ((word >> (bit_index - base)) & 1) != 0
        return values

    func read_raw_varint32() -> int:
        var shift := 0
        var result := 0
        for index in range(5):
            var value := read_byte()
            result |= (value & 0x7f) << shift
            if (value & 0x80) == 0:
                if index == 4 and (value & 0xf0) != 0:
                    push_error("unsigned int varint overflow")
                return result
            shift += 7
        push_error("unsigned int varint is too long")
        return 0

    func read_raw_varint64() -> int:
        var shift := 0
        var result := 0
        for index in range(10):
            var value := read_byte()
            result |= (value & 0x7f) << shift
            if (value & 0x80) == 0:
                if index == 9 and (value & 0xfe) != 0:
                    push_error("unsigned long varint overflow")
                return result
            shift += 7
        push_error("unsigned long varint is too long")
        return 0

    func _require_readable(length: int) -> void:
        if length < 0 or reader_index + length > limit:
            push_error("not enough readable bytes")


func _is_present(presence: Array, index: int) -> bool:
    return index >= 0 and index < presence.size() and presence[index]


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
        if _is_present(presence, ${field.presenceIndex?c}) and reader.has_remaining_in_object(object_end):
            message.${field.name} = ${field.readExpression}
<#else>
        if reader.has_remaining_in_object(object_end):
            message.${field.name} = ${field.readExpression}
</#if>
</#list>
        reader.end_object(object_end)
        return message


</#list>
