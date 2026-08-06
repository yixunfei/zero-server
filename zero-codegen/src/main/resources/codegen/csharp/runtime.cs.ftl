/*
 * ${generatedMarker}. Do not edit manually.
 */
using System;
using System.Collections.Generic;
using System.Text;

namespace ${namespace};

/// <summary>
/// 生成协议 payload DTO 的轻量标记接口。
/// </summary>
public interface IZeroGeneratedPayload
{
}

/// <summary>
/// zeroServer 二进制写入器。
/// </summary>
public sealed class ZeroWriter
{
    private const int MaxIntBytes = 5;
    private readonly List<byte> buffer = new List<byte>(128);

    /// <summary>返回已写入字节。</summary>
    public byte[] ToArray()
    {
        return buffer.ToArray();
    }

    /// <summary>写入 boolean。</summary>
    public void WriteBoolean(bool value)
    {
        WriteByte(value ? 1 : 0);
    }

    /// <summary>写入 byte。</summary>
    public void WriteByte(int value)
    {
        buffer.Add((byte)value);
    }

    /// <summary>写入 short，线格式为 ZigZag + VarInt。</summary>
    public void WriteShort(short value)
    {
        WriteInt(value);
    }

    /// <summary>写入 int，线格式为 ZigZag + VarInt。</summary>
    public void WriteInt(int value)
    {
        WriteRawVarInt32((uint)((value << 1) ^ (value >> 31)));
    }

    /// <summary>写入非负 int。</summary>
    public void WriteUnsignedInt(int value)
    {
        if (value < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(value), "unsigned int must not be negative");
        }
        WriteRawVarInt32((uint)value);
    }

    /// <summary>写入 long，线格式为 ZigZag + VarLong。</summary>
    public void WriteLong(long value)
    {
        WriteRawVarInt64(unchecked((ulong)((value << 1) ^ (value >> 63))));
    }

    /// <summary>写入非负 long。</summary>
    public void WriteUnsignedLong(ulong value)
    {
        WriteRawVarInt64(value);
    }

    /// <summary>写入 float，线格式为固定 4 字节大端。</summary>
    public void WriteFloat(float value)
    {
        WriteFixedInt(BitConverter.SingleToInt32Bits(value));
    }

    /// <summary>写入 double，线格式为固定 8 字节大端。</summary>
    public void WriteDouble(double value)
    {
        WriteFixedLong(BitConverter.DoubleToInt64Bits(value));
    }

    /// <summary>写入 UTF-8 字符串。</summary>
    public void WriteString(string value)
    {
        byte[] bytes = Encoding.UTF8.GetBytes(value ?? string.Empty);
        WriteByteArray(bytes);
    }

    /// <summary>写入长度前缀字节数组。</summary>
    public void WriteByteArray(byte[]? value)
    {
        if (value == null || value.Length == 0)
        {
            WriteUnsignedInt(0);
            return;
        }
        WriteUnsignedInt(value.Length);
        buffer.AddRange(value);
    }

    /// <summary>写入数组。</summary>
    public void WriteArray<T>(T[] values, Action<ZeroWriter, T> writer)
    {
        ArgumentNullException.ThrowIfNull(values);
        ArgumentNullException.ThrowIfNull(writer);
        WriteUnsignedInt(values.Length);
        foreach (T value in values)
        {
            writer(this, value);
        }
    }

    /// <summary>写入集合。</summary>
    public void WriteCollection<T>(ICollection<T> values, Action<ZeroWriter, T> writer)
    {
        ArgumentNullException.ThrowIfNull(values);
        ArgumentNullException.ThrowIfNull(writer);
        WriteUnsignedInt(values.Count);
        foreach (T value in values)
        {
            writer(this, value);
        }
    }

    /// <summary>写入 Map。</summary>
    public void WriteMap<K, V>(IDictionary<K, V> values, Action<ZeroWriter, K> keyWriter, Action<ZeroWriter, V> valueWriter)
        where K : notnull
    {
        ArgumentNullException.ThrowIfNull(values);
        ArgumentNullException.ThrowIfNull(keyWriter);
        ArgumentNullException.ThrowIfNull(valueWriter);
        WriteUnsignedInt(values.Count);
        foreach (KeyValuePair<K, V> entry in values)
        {
            keyWriter(this, entry.Key);
            valueWriter(this, entry.Value);
        }
    }

    /// <summary>写入 nullable 字段 presence bitmap。</summary>
    public void WritePresenceBits(int fieldCount, Func<int, bool> presentPredicate)
    {
        ArgumentNullException.ThrowIfNull(presentPredicate);
        if (fieldCount < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(fieldCount));
        }
        WriteUnsignedInt(fieldCount);
        int byteCount = (fieldCount + 7) / 8;
        for (int byteIndex = 0; byteIndex < byteCount; byteIndex++)
        {
            int word = 0;
            int baseIndex = byteIndex * 8;
            int end = Math.Min(fieldCount, baseIndex + 8);
            for (int bitIndex = baseIndex; bitIndex < end; bitIndex++)
            {
                if (presentPredicate(bitIndex))
                {
                    word |= 1 << (bitIndex - baseIndex);
                }
            }
            WriteByte(word);
        }
    }

    /// <summary>开始写入对象体。</summary>
    public int BeginObject()
    {
        int marker = buffer.Count;
        for (int index = 0; index < MaxIntBytes; index++)
        {
            buffer.Add(0);
        }
        return marker;
    }

    /// <summary>结束对象体并回填长度。</summary>
    public void EndObject(int marker)
    {
        if (marker < 0 || marker + MaxIntBytes > buffer.Count)
        {
            throw new ArgumentException("invalid object marker", nameof(marker));
        }
        int contentStart = marker + MaxIntBytes;
        int length = buffer.Count - contentStart;
        int lengthBytes = UnsignedIntSize(length);
        int shrink = MaxIntBytes - lengthBytes;
        if (shrink > 0)
        {
            buffer.RemoveRange(marker, shrink);
        }
        List<byte> encoded = new List<byte>(MaxIntBytes);
        WriteUnsignedIntTo(encoded, length);
        for (int index = 0; index < encoded.Count; index++)
        {
            buffer[marker + index] = encoded[index];
        }
    }

    private void WriteFixedInt(int value)
    {
        buffer.Add((byte)(value >> 24));
        buffer.Add((byte)(value >> 16));
        buffer.Add((byte)(value >> 8));
        buffer.Add((byte)value);
    }

    private void WriteFixedLong(long value)
    {
        for (int shift = 56; shift >= 0; shift -= 8)
        {
            buffer.Add((byte)(value >> shift));
        }
    }

    private void WriteRawVarInt32(uint value)
    {
        while ((value & ~0x7Fu) != 0)
        {
            buffer.Add((byte)((value & 0x7Fu) | 0x80u));
            value >>= 7;
        }
        buffer.Add((byte)value);
    }

    private void WriteRawVarInt64(ulong value)
    {
        while ((value & ~0x7Ful) != 0)
        {
            buffer.Add((byte)((value & 0x7Ful) | 0x80ul));
            value >>= 7;
        }
        buffer.Add((byte)value);
    }

    private static void WriteUnsignedIntTo(List<byte> target, int value)
    {
        uint remaining = (uint)value;
        while ((remaining & ~0x7Fu) != 0)
        {
            target.Add((byte)((remaining & 0x7Fu) | 0x80u));
            remaining >>= 7;
        }
        target.Add((byte)remaining);
    }

    private static int UnsignedIntSize(int value)
    {
        if ((value & ~0x7F) == 0)
        {
            return 1;
        }
        if ((value & ~0x3FFF) == 0)
        {
            return 2;
        }
        if ((value & ~0x1F_FFFF) == 0)
        {
            return 3;
        }
        if ((value & ~0x0FFF_FFFF) == 0)
        {
            return 4;
        }
        return 5;
    }
}

/// <summary>
/// zeroServer 二进制读取器。
/// </summary>
public sealed class ZeroReader
{
    private readonly byte[] buffer;
    private readonly int limit;
    private int readerIndex;

    /// <summary>创建读取器。</summary>
    public ZeroReader(byte[] bytes)
        : this(bytes, 0, bytes.Length)
    {
    }

    /// <summary>创建读取器。</summary>
    public ZeroReader(byte[] bytes, int offset, int length)
    {
        buffer = bytes ?? throw new ArgumentNullException(nameof(bytes));
        if (offset < 0 || length < 0 || offset + length > bytes.Length)
        {
            throw new ArgumentOutOfRangeException(nameof(length));
        }
        readerIndex = offset;
        limit = offset + length;
    }

    /// <summary>读取 boolean。</summary>
    public bool ReadBoolean()
    {
        return ReadByte() != 0;
    }

    /// <summary>读取 byte。</summary>
    public byte ReadByte()
    {
        RequireReadable(1);
        return buffer[readerIndex++];
    }

    /// <summary>读取 short。</summary>
    public short ReadShort()
    {
        return (short)ReadInt();
    }

    /// <summary>读取 int。</summary>
    public int ReadInt()
    {
        uint raw = ReadRawVarInt32();
        return (int)((raw >> 1) ^ (uint)-(int)(raw & 1u));
    }

    /// <summary>读取非负 int。</summary>
    public int ReadUnsignedInt()
    {
        uint value = ReadRawVarInt32();
        if (value > int.MaxValue)
        {
            throw new InvalidOperationException("unsigned int exceeds positive int range");
        }
        return (int)value;
    }

    /// <summary>读取 long。</summary>
    public long ReadLong()
    {
        ulong raw = ReadRawVarInt64();
        return unchecked((long)((raw >> 1) ^ (ulong)-(long)(raw & 1ul)));
    }

    /// <summary>读取非负 long。</summary>
    public ulong ReadUnsignedLong()
    {
        return ReadRawVarInt64();
    }

    /// <summary>读取 float。</summary>
    public float ReadFloat()
    {
        return BitConverter.Int32BitsToSingle(ReadFixedInt());
    }

    /// <summary>读取 double。</summary>
    public double ReadDouble()
    {
        return BitConverter.Int64BitsToDouble(ReadFixedLong());
    }

    /// <summary>读取 UTF-8 字符串。</summary>
    public string ReadString()
    {
        int length = ReadUnsignedInt();
        if (length == 0)
        {
            return string.Empty;
        }
        RequireReadable(length);
        string value = Encoding.UTF8.GetString(buffer, readerIndex, length);
        readerIndex += length;
        return value;
    }

    /// <summary>读取字节数组。</summary>
    public byte[] ReadByteArray()
    {
        int length = ReadUnsignedInt();
        if (length == 0)
        {
            return Array.Empty<byte>();
        }
        RequireReadable(length);
        byte[] value = new byte[length];
        Array.Copy(buffer, readerIndex, value, 0, length);
        readerIndex += length;
        return value;
    }

    /// <summary>读取数组。</summary>
    public T[] ReadArray<T>(Func<ZeroReader, T> reader)
    {
        ArgumentNullException.ThrowIfNull(reader);
        int count = ReadUnsignedInt();
        T[] values = new T[count];
        for (int index = 0; index < count; index++)
        {
            values[index] = reader(this);
        }
        return values;
    }

    /// <summary>读取列表。</summary>
    public List<T> ReadList<T>(Func<ZeroReader, T> reader)
    {
        ArgumentNullException.ThrowIfNull(reader);
        int count = ReadUnsignedInt();
        List<T> values = new List<T>(count);
        for (int index = 0; index < count; index++)
        {
            values.Add(reader(this));
        }
        return values;
    }

    /// <summary>读取集合。</summary>
    public HashSet<T> ReadSet<T>(Func<ZeroReader, T> reader)
        where T : notnull
    {
        ArgumentNullException.ThrowIfNull(reader);
        int count = ReadUnsignedInt();
        HashSet<T> values = new HashSet<T>();
        for (int index = 0; index < count; index++)
        {
            values.Add(reader(this));
        }
        return values;
    }

    /// <summary>读取 Map。</summary>
    public Dictionary<K, V> ReadMap<K, V>(Func<ZeroReader, K> keyReader, Func<ZeroReader, V> valueReader)
        where K : notnull
    {
        ArgumentNullException.ThrowIfNull(keyReader);
        ArgumentNullException.ThrowIfNull(valueReader);
        int count = ReadUnsignedInt();
        Dictionary<K, V> values = new Dictionary<K, V>(count);
        for (int index = 0; index < count; index++)
        {
            values[keyReader(this)] = valueReader(this);
        }
        return values;
    }

    /// <summary>读取对象体长度。</summary>
    public int BeginObject()
    {
        int length = ReadUnsignedInt();
        int end = readerIndex + length;
        if (end < readerIndex || end > limit)
        {
            throw new InvalidOperationException("object length exceeds readable bytes");
        }
        return end;
    }

    /// <summary>判断对象内是否还有可读字节。</summary>
    public bool HasRemainingInObject(int objectEnd)
    {
        if (objectEnd < readerIndex || objectEnd > limit)
        {
            throw new InvalidOperationException("invalid object end index");
        }
        return readerIndex < objectEnd;
    }

    /// <summary>结束对象体。</summary>
    public void EndObject(int objectEnd)
    {
        if (objectEnd < readerIndex || objectEnd > limit)
        {
            throw new InvalidOperationException("invalid object end index");
        }
        readerIndex = objectEnd;
    }

    /// <summary>读取 nullable 字段 presence bitmap。</summary>
    public bool[] ReadPresenceBits()
    {
        int fieldCount = ReadUnsignedInt();
        bool[] values = new bool[fieldCount];
        int byteCount = (fieldCount + 7) / 8;
        for (int byteIndex = 0; byteIndex < byteCount; byteIndex++)
        {
            int word = ReadByte();
            int baseIndex = byteIndex * 8;
            int end = Math.Min(fieldCount, baseIndex + 8);
            for (int bitIndex = baseIndex; bitIndex < end; bitIndex++)
            {
                values[bitIndex] = ((word >> (bitIndex - baseIndex)) & 1) != 0;
            }
        }
        return values;
    }

    private int ReadFixedInt()
    {
        RequireReadable(4);
        int value = (buffer[readerIndex] << 24)
                | (buffer[readerIndex + 1] << 16)
                | (buffer[readerIndex + 2] << 8)
                | buffer[readerIndex + 3];
        readerIndex += 4;
        return value;
    }

    private long ReadFixedLong()
    {
        RequireReadable(8);
        long value = 0L;
        for (int index = 0; index < 8; index++)
        {
            value = (value << 8) | buffer[readerIndex++];
        }
        return value;
    }

    private uint ReadRawVarInt32()
    {
        int shift = 0;
        uint result = 0;
        for (int index = 0; index < 5; index++)
        {
            byte value = ReadByte();
            result |= (uint)(value & 0x7F) << shift;
            if ((value & 0x80) == 0)
            {
                if (index == 4 && (value & 0xF0) != 0)
                {
                    throw new InvalidOperationException("unsigned int varint overflow");
                }
                return result;
            }
            shift += 7;
        }
        throw new InvalidOperationException("unsigned int varint is too long");
    }

    private ulong ReadRawVarInt64()
    {
        int shift = 0;
        ulong result = 0UL;
        for (int index = 0; index < 10; index++)
        {
            byte value = ReadByte();
            result |= (ulong)(value & 0x7F) << shift;
            if ((value & 0x80) == 0)
            {
                if (index == 9 && (value & 0xFE) != 0)
                {
                    throw new InvalidOperationException("unsigned long varint overflow");
                }
                return result;
            }
            shift += 7;
        }
        throw new InvalidOperationException("unsigned long varint is too long");
    }

    private void RequireReadable(int length)
    {
        if (length < 0 || readerIndex + length > limit)
        {
            throw new InvalidOperationException("not enough readable bytes");
        }
    }
}
