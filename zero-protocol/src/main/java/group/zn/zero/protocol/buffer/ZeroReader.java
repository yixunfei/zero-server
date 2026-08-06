package group.zn.zero.protocol.buffer;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.protocol.error.ProtocolErrorCode;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * zeroServer 二进制读取器。
 *
 * <p>读取器与 {@link ZeroWriter} 使用同一套线格式。对象读取通过长度边界支持尾部字段追加兼容。
 * 默认构造基于堆内 byte[]，也可以显式接入 ByteBuffer 或自定义 {@link ZeroBuffer}。
 * 所有方法线程不安全。
 *
 * @author zn
 */
public final class ZeroReader {

    /**
     * 底层缓冲区。
     */
    private final ZeroBuffer buffer;

    /**
     * 可读结束位置。
     */
    private final int limit;

    /**
     * 当前读取位置。
     */
    private int readerIndex;

    /**
     * 创建读取器。
     *
     * @param bytes 字节数组；不可为空。
     * @throws NullPointerException 当字节数组为空时抛出。
     */
    public ZeroReader(final byte[] bytes) {
        this(bytes, 0, Objects.requireNonNull(bytes, "bytes").length);
    }

    /**
     * 创建读取器。
     *
     * @param bytes 字节数组；不可为空。
     * @param offset 起始偏移。
     * @param length 可读长度。
     * @throws NullPointerException 当字节数组为空时抛出。
     * @throws IndexOutOfBoundsException 当偏移或长度非法时抛出。
     */
    public ZeroReader(final byte[] bytes, final int offset, final int length) {
        this(ZeroBuffers.wrap(Objects.requireNonNull(bytes, "bytes")), offset, length);
    }

    /**
     * 创建读取器。
     *
     * @param byteBuffer ByteBuffer；不可为空。
     * @throws NullPointerException 当 ByteBuffer 为空时抛出。
     */
    public ZeroReader(final ByteBuffer byteBuffer) {
        this(ZeroBuffers.wrap(byteBuffer));
    }

    /**
     * 创建读取器。
     *
     * @param buffer 底层缓冲区；不可为空。
     * @throws NullPointerException 当缓冲区为空时抛出。
     */
    public ZeroReader(final ZeroBuffer buffer) {
        this(buffer, 0, Objects.requireNonNull(buffer, "buffer").capacity());
    }

    /**
     * 创建读取器。
     *
     * @param buffer 底层缓冲区；不可为空。
     * @param offset 起始偏移。
     * @param length 可读长度。
     * @throws NullPointerException 当缓冲区为空时抛出。
     * @throws IndexOutOfBoundsException 当偏移或长度非法时抛出。
     */
    public ZeroReader(final ZeroBuffer buffer, final int offset, final int length) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        Objects.checkFromIndexSize(offset, length, buffer.capacity());
        this.readerIndex = offset;
        this.limit = offset + length;
    }

    /**
     * 返回当前读取位置。
     *
     * @return 当前读取位置；线程不安全，调用方需保证独占访问。
     */
    public int readerIndex() {
        return readerIndex;
    }

    /**
     * 返回剩余可读字节数。
     *
     * @return 剩余可读字节数；线程不安全，调用方需保证独占访问。
     */
    public int readableBytes() {
        return limit - readerIndex;
    }

    /**
     * 判断是否还有可读字节。
     *
     * @return true 表示还有可读字节；线程不安全，调用方需保证独占访问。
     */
    public boolean isReadable() {
        return readerIndex < limit;
    }

    /**
     * 读取 boolean。
     *
     * @return 读取值。
     */
    public boolean readBoolean() {
        return readByte() != 0;
    }

    /**
     * 读取 byte。
     *
     * @return 读取值。
     * @throws ZeroException 字节不足时抛出，必须绑定 ErrorCode。
     */
    public byte readByte() {
        requireReadable(1);
        return buffer.getByte(readerIndex++);
    }

    /**
     * 读取 short，线格式为 ZigZag + VarInt。
     *
     * @return 读取值。
     */
    public short readShort() {
        return (short) readInt();
    }

    /**
     * 读取 int，线格式为 ZigZag + VarInt。
     *
     * @return 读取值。
     */
    public int readInt() {
        int raw = readRawVarInt32();
        return (raw >>> 1) ^ -(raw & 1);
    }

    /**
     * 读取非负 int，线格式为 Unsigned VarInt。
     *
     * @return 读取值。
     * @throws ZeroException varint 非法时抛出，必须绑定 ErrorCode。
     */
    public int readUnsignedInt() {
        int value = readRawVarInt32();
        if (value < 0) {
            throw invalid("unsigned int exceeds positive int range");
        }
        return value;
    }

    /**
     * 读取 long，线格式为 ZigZag + VarLong。
     *
     * @return 读取值。
     */
    public long readLong() {
        long raw = readRawVarInt64();
        return (raw >>> 1) ^ -(raw & 1L);
    }

    /**
     * 读取非负 long，线格式为 Unsigned VarLong。
     *
     * @return 读取值。
     * @throws ZeroException varlong 非法时抛出，必须绑定 ErrorCode。
     */
    public long readUnsignedLong() {
        long value = readRawVarInt64();
        if (value < 0L) {
            throw invalid("unsigned long exceeds positive long range");
        }
        return value;
    }

    /**
     * 读取原始 VarInt32。
     *
     * @return 原始值。
     */
    private int readRawVarInt32() {
        int index = readerIndex;
        int shift = 0;
        int result = 0;
        for (int i = 0; i < 5; i++) {
            if (index >= limit) {
                throw invalid("not enough readable bytes");
            }
            int value = buffer.getByte(index++) & 0xFF;
            result |= (value & 0x7F) << shift;
            if ((value & 0x80) == 0) {
                if (i == 4 && (value & 0xF0) != 0) {
                    throw invalid("unsigned int varint overflow");
                }
                readerIndex = index;
                return result;
            }
            shift += 7;
        }
        throw invalid("unsigned int varint is too long");
    }

    /**
     * 读取原始 VarInt64。
     *
     * @return 原始值。
     */
    private long readRawVarInt64() {
        int index = readerIndex;
        int shift = 0;
        long result = 0L;
        for (int i = 0; i < 10; i++) {
            if (index >= limit) {
                throw invalid("not enough readable bytes");
            }
            int value = buffer.getByte(index++) & 0xFF;
            result |= (long) (value & 0x7F) << shift;
            if ((value & 0x80) == 0) {
                if (i == 9 && (value & 0xFE) != 0) {
                    throw invalid("unsigned long varint overflow");
                }
                readerIndex = index;
                return result;
            }
            shift += 7;
        }
        throw invalid("unsigned long varint is too long");
    }

    /**
     * 读取 float，线格式为固定 4 字节大端。
     *
     * @return 读取值。
     */
    public float readFloat() {
        return Float.intBitsToFloat(readFixedInt());
    }

    /**
     * 读取 double，线格式为固定 8 字节大端。
     *
     * @return 读取值。
     */
    public double readDouble() {
        return Double.longBitsToDouble(readFixedLong());
    }

    /**
     * 读取 UTF-8 字符串。
     *
     * @return 字符串；不可为空；线程不安全，调用方需保证独占访问。
     */
    public String readString() {
        int length = readUnsignedInt();
        if (length == 0) {
            return "";
        }
        requireReadable(length);
        String value = buffer.decodeString(readerIndex, length, StandardCharsets.UTF_8);
        readerIndex += length;
        return value;
    }

    /**
     * 读取 nullable UTF-8 字符串，使用 sizePlusOne 区分 null 与 empty。
     *
     * @return 字符串；可为空；线程不安全，调用方需保证独占访问。
     */
    public String readNullableString() {
        int lengthPlusOne = readUnsignedInt();
        if (lengthPlusOne == 0) {
            return null;
        }
        int length = lengthPlusOne - 1;
        if (length == 0) {
            return "";
        }
        requireReadable(length);
        String value = buffer.decodeString(readerIndex, length, StandardCharsets.UTF_8);
        readerIndex += length;
        return value;
    }

    /**
     * 读取字节数组。
     *
     * @return 字节数组副本；不可为空；有序；可能为空。
     */
    public byte[] readByteArray() {
        int length = readUnsignedInt();
        if (length == 0) {
            return new byte[0];
        }
        return readBytes(length);
    }

    /**
     * 读取 length-prefix 字节数组的借用切片。
     *
     * @return 借用切片；不可为空；有序；可能为空。
     */
    public ZeroBufferSlice readByteArrayView() {
        int length = readUnsignedInt();
        return length == 0 ? new ZeroBufferSlice(buffer, readerIndex, 0) : readBytesView(length);
    }

    /**
     * 读取 nullable 字节数组，使用 sizePlusOne 区分 null 与 empty。
     *
     * @return 字节数组副本；可为空；有序；可能为空数组。
     */
    public byte[] readNullableByteArray() {
        int lengthPlusOne = readUnsignedInt();
        if (lengthPlusOne == 0) {
            return null;
        }
        int length = lengthPlusOne - 1;
        if (length == 0) {
            return new byte[0];
        }
        return readBytes(length);
    }

    /**
     * 读取 nullable 字节数组的借用切片。
     *
     * @return 借用切片；可为空；有序；可能为空数组。
     */
    public ZeroBufferSlice readNullableByteArrayView() {
        int lengthPlusOne = readUnsignedInt();
        if (lengthPlusOne == 0) {
            return null;
        }
        int length = lengthPlusOne - 1;
        return length == 0 ? new ZeroBufferSlice(buffer, readerIndex, 0) : readBytesView(length);
    }

    /**
     * 读取指定长度字节。
     *
     * @param length 读取长度。
     * @return 字节数组副本；不可为空；有序；可能为空。
     * @throws ZeroException 字节不足或长度非法时抛出，必须绑定 ErrorCode。
     */
    public byte[] readBytes(final int length) {
        if (length < 0) {
            throw invalid("length must not be negative");
        }
        requireReadable(length);
        byte[] bytes = buffer.toByteArray(readerIndex, length);
        readerIndex += length;
        return bytes;
    }

    /**
     * 读取指定长度字节的借用切片。
     *
     * @param length 读取长度。
     * @return 借用切片；不可为空；有序；可能为空。
     * @throws ZeroException 字节不足或长度非法时抛出，必须绑定 ErrorCode。
     */
    public ZeroBufferSlice readBytesView(final int length) {
        if (length < 0) {
            throw invalid("length must not be negative");
        }
        requireReadable(length);
        ZeroBufferSlice slice = new ZeroBufferSlice(buffer, readerIndex, length);
        readerIndex += length;
        return slice;
    }

    /**
     * 开始读取对象体。
     *
     * @return 对象体结束位置；必须传给 endObject；线程不安全。
     * @throws ZeroException 对象长度非法时抛出，必须绑定 ErrorCode。
     */
    public int beginObject() {
        int length = readUnsignedInt();
        int endIndex = readerIndex + length;
        if (endIndex < readerIndex || endIndex > limit) {
            throw invalid("object length exceeds readable bytes");
        }
        return endIndex;
    }

    /**
     * 判断对象边界内是否还有可读字节。
     *
     * @param objectEnd beginObject 返回的对象结束位置。
     * @return true 表示对象边界内还有可读字节；线程不安全。
     * @throws ZeroException 对象边界非法时抛出，必须绑定 ErrorCode。
     */
    public boolean hasRemainingInObject(final int objectEnd) {
        if (objectEnd < readerIndex || objectEnd > limit) {
            throw invalid("invalid object end index");
        }
        return readerIndex < objectEnd;
    }

    /**
     * 结束对象体读取，跳过未知尾部字段。
     *
     * @param objectEnd beginObject 返回的对象结束位置。
     * @throws ZeroException 对象边界非法时抛出，必须绑定 ErrorCode。
     */
    public void endObject(final int objectEnd) {
        if (objectEnd < readerIndex || objectEnd > limit) {
            throw invalid("invalid object end index");
        }
        readerIndex = objectEnd;
    }

    /**
     * 跳过指定字节数。
     *
     * @param length 跳过长度。
     * @throws ZeroException 字节不足或长度非法时抛出，必须绑定 ErrorCode。
     */
    public void skip(final int length) {
        if (length < 0) {
            throw invalid("length must not be negative");
        }
        requireReadable(length);
        readerIndex += length;
    }

    /**
     * 读取 boolean 数组。
     *
     * @return boolean 数组；不可为空；有序；可能为空数组。
     */
    public boolean[] readBooleanArray() {
        int count = readSize();
        boolean[] values = new boolean[count];
        for (int index = 0; index < count; index++) {
            values[index] = readBoolean();
        }
        return values;
    }

    /**
     * 读取 nullable boolean 数组，使用 sizePlusOne 区分 null 与 empty。
     *
     * @return boolean 数组；可为空；有序；可能为空数组。
     */
    public boolean[] readNullableBooleanArray() {
        int count = readNullableSize();
        if (count < 0) {
            return null;
        }
        boolean[] values = new boolean[count];
        for (int index = 0; index < count; index++) {
            values[index] = readBoolean();
        }
        return values;
    }

    /**
     * 读取 short 数组。
     *
     * @return short 数组；不可为空；有序；可能为空数组。
     */
    public short[] readShortArray() {
        int count = readSize();
        short[] values = new short[count];
        for (int index = 0; index < count; index++) {
            values[index] = readShort();
        }
        return values;
    }

    /**
     * 读取 nullable short 数组，使用 sizePlusOne 区分 null 与 empty。
     *
     * @return short 数组；可为空；有序；可能为空数组。
     */
    public short[] readNullableShortArray() {
        int count = readNullableSize();
        if (count < 0) {
            return null;
        }
        short[] values = new short[count];
        for (int index = 0; index < count; index++) {
            values[index] = readShort();
        }
        return values;
    }

    /**
     * 读取 int 数组。
     *
     * @return int 数组；不可为空；有序；可能为空数组。
     */
    public int[] readIntArray() {
        int count = readSize();
        int[] values = new int[count];
        for (int index = 0; index < count; index++) {
            values[index] = readInt();
        }
        return values;
    }

    /**
     * 读取 nullable int 数组，使用 sizePlusOne 区分 null 与 empty。
     *
     * @return int 数组；可为空；有序；可能为空数组。
     */
    public int[] readNullableIntArray() {
        int count = readNullableSize();
        if (count < 0) {
            return null;
        }
        int[] values = new int[count];
        for (int index = 0; index < count; index++) {
            values[index] = readInt();
        }
        return values;
    }

    /**
     * 读取 long 数组。
     *
     * @return long 数组；不可为空；有序；可能为空数组。
     */
    public long[] readLongArray() {
        int count = readSize();
        long[] values = new long[count];
        for (int index = 0; index < count; index++) {
            values[index] = readLong();
        }
        return values;
    }

    /**
     * 读取 nullable long 数组，使用 sizePlusOne 区分 null 与 empty。
     *
     * @return long 数组；可为空；有序；可能为空数组。
     */
    public long[] readNullableLongArray() {
        int count = readNullableSize();
        if (count < 0) {
            return null;
        }
        long[] values = new long[count];
        for (int index = 0; index < count; index++) {
            values[index] = readLong();
        }
        return values;
    }

    /**
     * 读取 float 数组。
     *
     * @return float 数组；不可为空；有序；可能为空数组。
     */
    public float[] readFloatArray() {
        int count = readSize();
        float[] values = new float[count];
        for (int index = 0; index < count; index++) {
            values[index] = readFloat();
        }
        return values;
    }

    /**
     * 读取 nullable float 数组，使用 sizePlusOne 区分 null 与 empty。
     *
     * @return float 数组；可为空；有序；可能为空数组。
     */
    public float[] readNullableFloatArray() {
        int count = readNullableSize();
        if (count < 0) {
            return null;
        }
        float[] values = new float[count];
        for (int index = 0; index < count; index++) {
            values[index] = readFloat();
        }
        return values;
    }

    /**
     * 读取 double 数组。
     *
     * @return double 数组；不可为空；有序；可能为空数组。
     */
    public double[] readDoubleArray() {
        int count = readSize();
        double[] values = new double[count];
        for (int index = 0; index < count; index++) {
            values[index] = readDouble();
        }
        return values;
    }

    /**
     * 读取 nullable double 数组，使用 sizePlusOne 区分 null 与 empty。
     *
     * @return double 数组；可为空；有序；可能为空数组。
     */
    public double[] readNullableDoubleArray() {
        int count = readNullableSize();
        if (count < 0) {
            return null;
        }
        double[] values = new double[count];
        for (int index = 0; index < count; index++) {
            values[index] = readDouble();
        }
        return values;
    }

    /**
     * 读取对象数组。
     *
     * @param elementType 元素类型；不可为空。
     * @param elementReader 元素读取器；不可为空。
     * @param <T> 元素类型。
     * @return 对象数组；不可为空；有序；可能为空数组。
     * @throws NullPointerException 当元素类型或读取器为空时抛出。
     */
    @SuppressWarnings("unchecked")
    public <T> T[] readArray(final Class<T> elementType, final Function<ZeroReader, T> elementReader) {
        Objects.requireNonNull(elementType, "elementType");
        Objects.requireNonNull(elementReader, "elementReader");
        int count = readSize();
        T[] values = (T[]) Array.newInstance(elementType, count);
        for (int index = 0; index < count; index++) {
            values[index] = elementReader.apply(this);
        }
        return values;
    }

    /**
     * 读取 nullable 对象数组，使用 sizePlusOne 区分 null 与 empty。
     *
     * @param elementType 元素类型；不可为空。
     * @param elementReader 元素读取器；不可为空。
     * @param <T> 元素类型。
     * @return 对象数组；可为空；有序；可能为空数组。
     * @throws NullPointerException 当元素类型或读取器为空时抛出。
     */
    @SuppressWarnings("unchecked")
    public <T> T[] readNullableArray(final Class<T> elementType, final Function<ZeroReader, T> elementReader) {
        Objects.requireNonNull(elementType, "elementType");
        Objects.requireNonNull(elementReader, "elementReader");
        int count = readNullableSize();
        if (count < 0) {
            return null;
        }
        T[] values = (T[]) Array.newInstance(elementType, count);
        for (int index = 0; index < count; index++) {
            values[index] = elementReader.apply(this);
        }
        return values;
    }

    /**
     * 读取列表。
     *
     * @param elementReader 元素读取器；不可为空。
     * @param <T> 元素类型。
     * @return ArrayList；不可为空；有序；可能为空。
     * @throws NullPointerException 当读取器为空时抛出。
     */
    public <T> List<T> readList(final Function<ZeroReader, T> elementReader) {
        return readCollection(ArrayList::new, elementReader);
    }

    /**
     * 读取 nullable 列表，使用 sizePlusOne 区分 null 与 empty。
     *
     * @param elementReader 元素读取器；不可为空。
     * @param <T> 元素类型。
     * @return ArrayList；可为空；有序；可能为空。
     * @throws NullPointerException 当读取器为空时抛出。
     */
    public <T> List<T> readNullableList(final Function<ZeroReader, T> elementReader) {
        return readNullableCollection(ArrayList::new, elementReader);
    }

    /**
     * 读取 Set。
     *
     * @param elementReader 元素读取器；不可为空。
     * @param <T> 元素类型。
     * @return HashSet；不可为空；不保证读取顺序；可能为空。
     * @throws NullPointerException 当读取器为空时抛出。
     */
    public <T> Set<T> readSet(final Function<ZeroReader, T> elementReader) {
        return readCollection(HashSet::new, elementReader);
    }

    /**
     * 读取 nullable Set，使用 sizePlusOne 区分 null 与 empty。
     *
     * @param elementReader 元素读取器；不可为空。
     * @param <T> 元素类型。
     * @return HashSet；可为空；不保证读取顺序；可能为空。
     * @throws NullPointerException 当读取器为空时抛出。
     */
    public <T> Set<T> readNullableSet(final Function<ZeroReader, T> elementReader) {
        return readNullableCollection(HashSet::new, elementReader);
    }

    /**
     * 读取集合。
     *
     * @param collectionFactory 集合工厂；不可为空。
     * @param elementReader 元素读取器；不可为空。
     * @param <T> 元素类型。
     * @param <C> 集合类型。
     * @return 集合；不可为空；可能为空。
     * @throws NullPointerException 当工厂或读取器为空时抛出。
     */
    public <T, C extends Collection<T>> C readCollection(
            final IntFunction<C> collectionFactory,
            final Function<ZeroReader, T> elementReader) {
        Objects.requireNonNull(collectionFactory, "collectionFactory");
        Objects.requireNonNull(elementReader, "elementReader");
        int count = readSize();
        C values = collectionFactory.apply(count);
        for (int index = 0; index < count; index++) {
            values.add(elementReader.apply(this));
        }
        return values;
    }

    /**
     * 读取 nullable 集合，使用 sizePlusOne 区分 null 与 empty。
     *
     * @param collectionFactory 集合工厂；不可为空。
     * @param elementReader 元素读取器；不可为空。
     * @param <T> 元素类型。
     * @param <C> 集合类型。
     * @return 集合；可为空；可能为空集合。
     * @throws NullPointerException 当工厂或读取器为空时抛出。
     */
    public <T, C extends Collection<T>> C readNullableCollection(
            final IntFunction<C> collectionFactory,
            final Function<ZeroReader, T> elementReader) {
        Objects.requireNonNull(collectionFactory, "collectionFactory");
        Objects.requireNonNull(elementReader, "elementReader");
        int count = readNullableSize();
        if (count < 0) {
            return null;
        }
        C values = collectionFactory.apply(count);
        for (int index = 0; index < count; index++) {
            values.add(elementReader.apply(this));
        }
        return values;
    }

    /**
     * 读取 Map。
     *
     * @param keyReader key 读取器；不可为空。
     * @param valueReader value 读取器；不可为空。
     * @param <K> key 类型。
     * @param <V> value 类型。
     * @return HashMap；不可为空；不保证读取顺序；可能为空。
     * @throws NullPointerException 当读取器为空时抛出。
     */
    public <K, V> Map<K, V> readMap(
            final Function<ZeroReader, K> keyReader,
            final Function<ZeroReader, V> valueReader) {
        return readMap(HashMap::new, keyReader, valueReader);
    }

    /**
     * 读取 nullable Map，使用 sizePlusOne 区分 null 与 empty。
     *
     * @param keyReader key 读取器；不可为空。
     * @param valueReader value 读取器；不可为空。
     * @param <K> key 类型。
     * @param <V> value 类型。
     * @return LinkedHashMap；可为空；保持线格式读取顺序；可能为空。
     * @throws NullPointerException 当读取器为空时抛出。
     */
    public <K, V> Map<K, V> readNullableMap(
            final Function<ZeroReader, K> keyReader,
            final Function<ZeroReader, V> valueReader) {
        return readNullableMap(LinkedHashMap::new, keyReader, valueReader);
    }

    /**
     * 读取 Map。
     *
     * @param mapFactory Map 工厂；不可为空。
     * @param keyReader key 读取器；不可为空。
     * @param valueReader value 读取器；不可为空。
     * @param <K> key 类型。
     * @param <V> value 类型。
     * @param <M> Map 类型。
     * @return Map；不可为空；可能为空。
     * @throws NullPointerException 当工厂或读取器为空时抛出。
     */
    public <K, V, M extends Map<K, V>> M readMap(
            final IntFunction<M> mapFactory,
            final Function<ZeroReader, K> keyReader,
            final Function<ZeroReader, V> valueReader) {
        Objects.requireNonNull(mapFactory, "mapFactory");
        Objects.requireNonNull(keyReader, "keyReader");
        Objects.requireNonNull(valueReader, "valueReader");
        int count = readSize();
        M values = mapFactory.apply(count);
        for (int index = 0; index < count; index++) {
            K key = keyReader.apply(this);
            V value = valueReader.apply(this);
            values.put(key, value);
        }
        return values;
    }

    /**
     * 读取 nullable Map，使用 sizePlusOne 区分 null 与 empty。
     *
     * @param mapFactory Map 工厂；不可为空。
     * @param keyReader key 读取器；不可为空。
     * @param valueReader value 读取器；不可为空。
     * @param <K> key 类型。
     * @param <V> value 类型。
     * @param <M> Map 类型。
     * @return Map；可为空；可能为空 Map。
     * @throws NullPointerException 当工厂或读取器为空时抛出。
     */
    public <K, V, M extends Map<K, V>> M readNullableMap(
            final IntFunction<M> mapFactory,
            final Function<ZeroReader, K> keyReader,
            final Function<ZeroReader, V> valueReader) {
        Objects.requireNonNull(mapFactory, "mapFactory");
        Objects.requireNonNull(keyReader, "keyReader");
        Objects.requireNonNull(valueReader, "valueReader");
        int count = readNullableSize();
        if (count < 0) {
            return null;
        }
        M values = mapFactory.apply(count);
        for (int index = 0; index < count; index++) {
            K key = keyReader.apply(this);
            V value = valueReader.apply(this);
            values.put(key, value);
        }
        return values;
    }

    /**
     * 读取 nullable 字段 presence bitmap。
     *
     * @return 布尔数组；不可为空；有序；可能为空数组。
     */
    public boolean[] readPresenceBits() {
        int fieldCount = readSize();
        boolean[] values = new boolean[fieldCount];
        int byteCount = (fieldCount + Byte.SIZE - 1) / Byte.SIZE;
        for (int byteIndex = 0; byteIndex < byteCount; byteIndex++) {
            int word = readByte() & 0xFF;
            int base = byteIndex * Byte.SIZE;
            int end = Math.min(fieldCount, base + Byte.SIZE);
            for (int bitIndex = base; bitIndex < end; bitIndex++) {
                values[bitIndex] = ((word >>> (bitIndex - base)) & 1) != 0;
            }
        }
        return values;
    }

    /**
     * 读取 size。
     *
     * @return size 值。
     */
    private int readSize() {
        return readUnsignedInt();
    }

    /**
     * 读取 nullable size。
     *
     * @return size；-1 表示 null。
     */
    private int readNullableSize() {
        int sizePlusOne = readUnsignedInt();
        if (sizePlusOne == 0) {
            return -1;
        }
        return sizePlusOne - 1;
    }

    /**
     * 读取固定 4 字节大端整数。
     *
     * @return 整数。
     */
    private int readFixedInt() {
        requireReadable(Integer.BYTES);
        int value = ((buffer.getByte(readerIndex) & 0xFF) << 24)
                | ((buffer.getByte(readerIndex + 1) & 0xFF) << 16)
                | ((buffer.getByte(readerIndex + 2) & 0xFF) << 8)
                | (buffer.getByte(readerIndex + 3) & 0xFF);
        readerIndex += Integer.BYTES;
        return value;
    }

    /**
     * 读取固定 8 字节大端 long。
     *
     * @return long 值。
     */
    private long readFixedLong() {
        requireReadable(Long.BYTES);
        long value = 0L;
        for (int index = 0; index < Long.BYTES; index++) {
            value = (value << 8) | (buffer.getByte(readerIndex++) & 0xFFL);
        }
        return value;
    }

    /**
     * 校验可读长度。
     *
     * @param length 长度。
     * @throws ZeroException 当剩余字节不足时抛出。
     */
    private void requireReadable(final int length) {
        if (length < 0) {
            throw invalid("length must not be negative");
        }
        int remaining = limit - readerIndex;
        if (remaining < length) {
            throw invalid("not enough readable bytes");
        }
    }

    /**
     * 创建协议解码异常。
     *
     * @param message 错误信息。
     * @return 异常实例。
     */
    private ZeroException invalid(final String message) {
        return ZeroException.of(ProtocolErrorCode.DECODE_FAILED, message, null);
    }
}
