package group.zn.zero.protocol.buffer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.IntPredicate;

/**
 * zeroServer 二进制写入器。
 *
 * <p>写入器面向生成代码设计，默认使用堆内 byte[] 缓冲区，也可以显式选择
 * DirectByteBuffer 或 native memory。整数采用 ZigZag + VarInt，长度、数量、协议 ID 等
 * 天然非负值采用 Unsigned VarInt。所有方法线程不安全。
 *
 * @author zn
 */
public final class ZeroWriter implements AutoCloseable {

    /**
     * 默认缓冲区大小。
     */
    private static final int DEFAULT_CAPACITY = 128;

    /**
     * int varint 最大字节数。
     */
    private static final int MAX_INT_BYTES = 5;

    /**
     * 底层缓冲区。
     */
    private final ZeroBuffer buffer;

    /**
     * 写入位置。
     */
    private int writerIndex;

    /**
     * 创建默认写入器。
     */
    public ZeroWriter() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 创建指定初始容量的堆内写入器。
     *
     * @param initialCapacity 初始容量。
     * @throws IllegalArgumentException 当初始容量为负数时抛出。
     */
    public ZeroWriter(final int initialCapacity) {
        this(ZeroBuffers.heap(initialCapacity));
    }

    /**
     * 创建指定缓冲区的写入器。
     *
     * @param buffer 底层缓冲区；不可为空。
     * @throws NullPointerException 当缓冲区为空时抛出。
     */
    public ZeroWriter(final ZeroBuffer buffer) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
    }

    /**
     * 创建指定 ByteBuffer 的写入器。
     *
     * <p>direct ByteBuffer 会零拷贝借用；非 direct ByteBuffer 会复制为堆内缓冲区。
     *
     * @param byteBuffer ByteBuffer；不可为空。
     * @throws NullPointerException 当 ByteBuffer 为空时抛出。
     */
    public ZeroWriter(final ByteBuffer byteBuffer) {
        this(ZeroBuffers.wrap(byteBuffer));
    }

    /**
     * 创建 direct 写入器。
     *
     * @param initialCapacity 初始容量。
     * @return direct 写入器；不可为空；线程不安全。
     */
    public static ZeroWriter direct(final int initialCapacity) {
        return new ZeroWriter(ZeroBuffers.direct(initialCapacity));
    }

    /**
     * 创建 native memory 写入器。
     *
     * @param initialCapacity 初始容量。
     * @return native memory 写入器；不可为空；线程不安全。
     */
    public static ZeroWriter nativeMemory(final int initialCapacity) {
        return new ZeroWriter(ZeroBuffers.nativeMemory(initialCapacity));
    }

    /**
     * 返回当前写入位置。
     *
     * @return 当前写入位置；线程不安全，调用方需保证独占访问。
     */
    public int writerIndex() {
        return writerIndex;
    }

    /**
     * 返回底层缓冲区容量。
     *
     * @return 容量；线程不安全。
     */
    public int capacity() {
        return buffer.capacity();
    }

    /**
     * 返回底层缓冲区。
     *
     * <p>该方法暴露的是 live buffer，不是副本。调用方若继续写入或释放底层缓冲区，
     * 会直接影响当前 writer 的状态。
     *
     * @return 底层缓冲区；不可为空；线程不安全。
     */
    public ZeroBuffer buffer() {
        return buffer;
    }

    /**
     * 重置写入位置。
     *
     * <p>重置只会清空逻辑写入长度，不会擦除底层内存内容，也不会释放缓冲区。
     */
    public void reset() {
        writerIndex = 0;
    }

    /**
     * 创建当前已写入区域的借用切片。
     *
     * @return 借用切片；不可为空；线程不安全。
     */
    public ZeroBufferSlice toBufferSlice() {
        return new ZeroBufferSlice(buffer, 0, writerIndex);
    }

    /**
     * 返回当前已写入区域的只读 ByteBuffer 视图。
     *
     * @return 只读 ByteBuffer；不可为空；线程不安全。
     */
    public ByteBuffer toByteBuffer() {
        return buffer.toByteBuffer(0, writerIndex);
    }

    /**
     * 返回已写入字节副本。
     *
     * @return 字节副本；不可为空；有序；可能为空；线程安全。
     */
    public byte[] toByteArray() {
        return buffer.toByteArray(0, writerIndex);
    }

    /**
     * 将已写入字节复制到目标数组。
     *
     * @param target 目标数组；不可为空。
     * @param targetOffset 目标起始偏移。
     * @throws NullPointerException 当目标数组为空时抛出。
     * @throws IndexOutOfBoundsException 当目标范围越界时抛出。
     */
    public void copyTo(final byte[] target, final int targetOffset) {
        buffer.getBytes(0, Objects.requireNonNull(target, "target"), targetOffset, writerIndex);
    }

    /**
     * 判断是否基于 direct memory。
     *
     * @return true 表示底层是 DirectByteBuffer；线程安全。
     */
    public boolean isDirect() {
        return buffer.isDirect();
    }

    /**
     * 判断是否基于 native memory。
     *
     * @return true 表示底层是手动 native memory；线程安全。
     */
    public boolean isNativeMemory() {
        return buffer.isNativeMemory();
    }

    /**
     * 写入 boolean。
     *
     * @param value 待写入值。
     */
    public void writeBoolean(final boolean value) {
        writeByte(value ? 1 : 0);
    }

    /**
     * 写入单字节。
     *
     * @param value 待写入值。
     */
    public void writeByte(final int value) {
        ensureWritable(1);
        buffer.putByte(writerIndex++, (byte) value);
    }

    /**
     * 写入 short，线格式采用 ZigZag + VarInt。
     *
     * @param value 待写入值。
     */
    public void writeShort(final short value) {
        writeInt(value);
    }

    /**
     * 写入 int，线格式采用 ZigZag + VarInt。
     *
     * @param value 待写入值。
     */
    public void writeInt(final int value) {
        writeRawVarInt32((value << 1) ^ (value >> 31));
    }

    /**
     * 写入非负 int，线格式采用 Unsigned VarInt。
     *
     * @param value 非负整数。
     * @throws IllegalArgumentException 当 value 为负数时抛出。
     */
    public void writeUnsignedInt(final int value) {
        if (value < 0) {
            throw new IllegalArgumentException("unsigned int must not be negative");
        }
        ensureWritable(unsignedIntSize(value));
        writerIndex += writeUnsignedIntAt(writerIndex, value);
    }

    /**
     * 写入 long，线格式采用 ZigZag + VarLong。
     *
     * @param value 待写入值。
     */
    public void writeLong(final long value) {
        writeRawVarInt64((value << 1) ^ (value >> 63));
    }

    /**
     * 写入非负 long，线格式采用 Unsigned VarLong。
     *
     * @param value 非负长整数。
     * @throws IllegalArgumentException 当 value 为负数时抛出。
     */
    public void writeUnsignedLong(final long value) {
        if (value < 0L) {
            throw new IllegalArgumentException("unsigned long must not be negative");
        }
        ensureWritable(10);
        long remaining = value;
        while ((remaining & ~0x7FL) != 0L) {
            buffer.putByte(writerIndex++, (byte) (((int) remaining & 0x7F) | 0x80));
            remaining >>>= 7;
        }
        buffer.putByte(writerIndex++, (byte) remaining);
    }

    /**
     * 写入 float，线格式为固定 4 字节大端。
     *
     * @param value 待写入值。
     */
    public void writeFloat(final float value) {
        writeFixedInt(Float.floatToIntBits(value));
    }

    /**
     * 写入 double，线格式为固定 8 字节大端。
     *
     * @param value 待写入值。
     */
    public void writeDouble(final double value) {
        writeFixedLong(Double.doubleToLongBits(value));
    }

    /**
     * 写入 UTF-8 字符串。
     *
     * @param value 字符串；不可为空。
     * @throws NullPointerException 当字符串为空时抛出。
     */
    public void writeString(final String value) {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty()) {
            writeUnsignedInt(0);
            return;
        }
        writeByteArray(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 写入 nullable UTF-8 字符串，使用 sizePlusOne 区分 null 与 empty。
     *
     * @param value 字符串；可为空。
     */
    public void writeNullableString(final String value) {
        if (value == null) {
            writeUnsignedInt(0);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeNullableSize(bytes.length);
        writeBytes(bytes);
    }

    /**
     * 写入字节数组，先写长度再写内容。
     *
     * @param bytes 字节数组；为空时按空数组写入。
     */
    public void writeByteArray(final byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            writeUnsignedInt(0);
            return;
        }
        writeUnsignedInt(bytes.length);
        writeBytes(bytes);
    }

    /**
     * 写入字节数组全部内容。
     *
     * @param bytes 字节数组；不可为空。
     * @throws NullPointerException 当字节数组为空时抛出。
     */
    public void writeBytes(final byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        writeBytes(bytes, 0, bytes.length);
    }

    /**
     * 写入只读或可写视图的剩余数据，不修改源游标；线程不安全，修改本 writer。
     * @param source 来源，不可为空；调用期间须保持内容稳定。
     * @throws NullPointerException 来源为空。
     */
    public void writeBytes(final ByteBuffer source) {
        int length = Objects.requireNonNull(source, "source").remaining();
        ensureWritable(length);
        buffer.putBytes(writerIndex, source);
        writerIndex += length;
    }

    /**
     * 写入字节数组片段。
     *
     * @param bytes 字节数组；不可为空。
     * @param offset 起始偏移。
     * @param length 写入长度。
     * @throws NullPointerException 当字节数组为空时抛出。
     * @throws IndexOutOfBoundsException 当偏移或长度非法时抛出。
     */
    public void writeBytes(final byte[] bytes, final int offset, final int length) {
        Objects.checkFromIndexSize(offset, length, Objects.requireNonNull(bytes, "bytes").length);
        ensureWritable(length);
        buffer.putBytes(writerIndex, bytes, offset, length);
        writerIndex += length;
    }

    /**
     * 写入 nullable 字节数组，使用 sizePlusOne 区分 null 与 empty。
     *
     * @param bytes 字节数组；可为空。
     */
    public void writeNullableByteArray(final byte[] bytes) {
        if (bytes == null) {
            writeUnsignedInt(0);
            return;
        }
        writeNullableSize(bytes.length);
        writeBytes(bytes);
    }

    /**
     * 写入 boolean 数组。
     *
     * @param values boolean 数组；不可为空。
     * @throws NullPointerException 当数组为空时抛出。
     */
    public void writeBooleanArray(final boolean[] values) {
        int count = Objects.requireNonNull(values, "values").length;
        writeUnsignedInt(count);
        for (int index = 0; index < count; index++) {
            writeBoolean(values[index]);
        }
    }

    /**
     * 写入 nullable boolean 数组，使用 sizePlusOne 区分 null 与 empty。
     *
     * @param values boolean 数组；可为空。
     */
    public void writeNullableBooleanArray(final boolean[] values) {
        if (values == null) {
            writeUnsignedInt(0);
            return;
        }
        writeNullableSize(values.length);
        for (boolean value : values) {
            writeBoolean(value);
        }
    }

    /**
     * 写入 short 数组。
     *
     * @param values short 数组；不可为空。
     * @throws NullPointerException 当数组为空时抛出。
     */
    public void writeShortArray(final short[] values) {
        int count = Objects.requireNonNull(values, "values").length;
        writeUnsignedInt(count);
        for (int index = 0; index < count; index++) {
            writeShort(values[index]);
        }
    }

    /**
     * 写入 nullable short 数组，使用 sizePlusOne 区分 null 与 empty。
     *
     * @param values short 数组；可为空。
     */
    public void writeNullableShortArray(final short[] values) {
        if (values == null) {
            writeUnsignedInt(0);
            return;
        }
        writeNullableSize(values.length);
        for (short value : values) {
            writeShort(value);
        }
    }

    /**
     * 写入 int 数组。
     *
     * @param values int 数组；不可为空。
     * @throws NullPointerException 当数组为空时抛出。
     */
    public void writeIntArray(final int[] values) {
        int count = Objects.requireNonNull(values, "values").length;
        writeUnsignedInt(count);
        for (int index = 0; index < count; index++) {
            writeInt(values[index]);
        }
    }

    /**
     * 写入 nullable int 数组，使用 sizePlusOne 区分 null 与 empty。
     *
     * @param values int 数组；可为空。
     */
    public void writeNullableIntArray(final int[] values) {
        if (values == null) {
            writeUnsignedInt(0);
            return;
        }
        writeNullableSize(values.length);
        for (int value : values) {
            writeInt(value);
        }
    }

    /**
     * 写入 long 数组。
     *
     * @param values long 数组；不可为空。
     * @throws NullPointerException 当数组为空时抛出。
     */
    public void writeLongArray(final long[] values) {
        int count = Objects.requireNonNull(values, "values").length;
        writeUnsignedInt(count);
        for (int index = 0; index < count; index++) {
            writeLong(values[index]);
        }
    }

    /**
     * 写入 nullable long 数组，使用 sizePlusOne 区分 null 与 empty。
     *
     * @param values long 数组；可为空。
     */
    public void writeNullableLongArray(final long[] values) {
        if (values == null) {
            writeUnsignedInt(0);
            return;
        }
        writeNullableSize(values.length);
        for (long value : values) {
            writeLong(value);
        }
    }

    /**
     * 写入 float 数组。
     *
     * @param values float 数组；不可为空。
     * @throws NullPointerException 当数组为空时抛出。
     */
    public void writeFloatArray(final float[] values) {
        int count = Objects.requireNonNull(values, "values").length;
        writeUnsignedInt(count);
        for (int index = 0; index < count; index++) {
            writeFloat(values[index]);
        }
    }

    /**
     * 写入 nullable float 数组，使用 sizePlusOne 区分 null 与 empty。
     *
     * @param values float 数组；可为空。
     */
    public void writeNullableFloatArray(final float[] values) {
        if (values == null) {
            writeUnsignedInt(0);
            return;
        }
        writeNullableSize(values.length);
        for (float value : values) {
            writeFloat(value);
        }
    }

    /**
     * 写入 double 数组。
     *
     * @param values double 数组；不可为空。
     * @throws NullPointerException 当数组为空时抛出。
     */
    public void writeDoubleArray(final double[] values) {
        int count = Objects.requireNonNull(values, "values").length;
        writeUnsignedInt(count);
        for (int index = 0; index < count; index++) {
            writeDouble(values[index]);
        }
    }

    /**
     * 写入 nullable double 数组，使用 sizePlusOne 区分 null 与 empty。
     *
     * @param values double 数组；可为空。
     */
    public void writeNullableDoubleArray(final double[] values) {
        if (values == null) {
            writeUnsignedInt(0);
            return;
        }
        writeNullableSize(values.length);
        for (double value : values) {
            writeDouble(value);
        }
    }

    /**
     * 写入对象数组。
     *
     * @param values 对象数组；不可为空。
     * @param elementWriter 元素写入器；不可为空。
     * @param <T> 元素类型。
     * @throws NullPointerException 当数组或元素写入器为空时抛出。
     */
    public <T> void writeArray(final T[] values, final BiConsumer<ZeroWriter, T> elementWriter) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(elementWriter, "elementWriter");
        int count = values.length;
        writeUnsignedInt(count);
        for (int index = 0; index < count; index++) {
            elementWriter.accept(this, values[index]);
        }
    }

    /**
     * 写入 nullable 对象数组，使用 sizePlusOne 区分 null 与 empty。
     *
     * @param values 对象数组；可为空。
     * @param elementWriter 元素写入器；不可为空。
     * @param <T> 元素类型。
     * @throws NullPointerException 当元素写入器为空时抛出。
     */
    public <T> void writeNullableArray(final T[] values, final BiConsumer<ZeroWriter, T> elementWriter) {
        Objects.requireNonNull(elementWriter, "elementWriter");
        if (values == null) {
            writeUnsignedInt(0);
            return;
        }
        writeNullableSize(values.length);
        for (T value : values) {
            elementWriter.accept(this, value);
        }
    }

    /**
     * 写入集合。
     *
     * @param values 集合；不可为空。
     * @param elementWriter 元素写入器；不可为空。
     * @param <T> 元素类型。
     * @throws NullPointerException 当集合或元素写入器为空时抛出。
     */
    public <T> void writeCollection(final Collection<T> values, final BiConsumer<ZeroWriter, T> elementWriter) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(elementWriter, "elementWriter");
        int count = values.size();
        writeUnsignedInt(count);
        for (T value : values) {
            elementWriter.accept(this, value);
        }
    }

    /**
     * 写入 nullable 集合，使用 sizePlusOne 区分 null 与 empty。
     *
     * @param values 集合；可为空。
     * @param elementWriter 元素写入器；不可为空。
     * @param <T> 元素类型。
     * @throws NullPointerException 当元素写入器为空时抛出。
     */
    public <T> void writeNullableCollection(final Collection<T> values, final BiConsumer<ZeroWriter, T> elementWriter) {
        Objects.requireNonNull(elementWriter, "elementWriter");
        if (values == null) {
            writeUnsignedInt(0);
            return;
        }
        writeNullableSize(values.size());
        for (T value : values) {
            elementWriter.accept(this, value);
        }
    }

    /**
     * 写入 Map。
     *
     * @param values Map；不可为空。
     * @param keyWriter key 写入器；不可为空。
     * @param valueWriter value 写入器；不可为空。
     * @param <K> key 类型。
     * @param <V> value 类型。
     * @throws NullPointerException 当 Map 或写入器为空时抛出。
     */
    public <K, V> void writeMap(
            final Map<K, V> values,
            final BiConsumer<ZeroWriter, K> keyWriter,
            final BiConsumer<ZeroWriter, V> valueWriter) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(keyWriter, "keyWriter");
        Objects.requireNonNull(valueWriter, "valueWriter");
        int count = values.size();
        writeUnsignedInt(count);
        for (Map.Entry<K, V> entry : values.entrySet()) {
            keyWriter.accept(this, entry.getKey());
            valueWriter.accept(this, entry.getValue());
        }
    }

    /**
     * 写入 nullable Map，使用 sizePlusOne 区分 null 与 empty。
     *
     * @param values Map；可为空。
     * @param keyWriter key 写入器；不可为空。
     * @param valueWriter value 写入器；不可为空。
     * @param <K> key 类型。
     * @param <V> value 类型。
     * @throws NullPointerException 当写入器为空时抛出。
     */
    public <K, V> void writeNullableMap(
            final Map<K, V> values,
            final BiConsumer<ZeroWriter, K> keyWriter,
            final BiConsumer<ZeroWriter, V> valueWriter) {
        Objects.requireNonNull(keyWriter, "keyWriter");
        Objects.requireNonNull(valueWriter, "valueWriter");
        if (values == null) {
            writeUnsignedInt(0);
            return;
        }
        writeNullableSize(values.size());
        for (Map.Entry<K, V> entry : values.entrySet()) {
            keyWriter.accept(this, entry.getKey());
            valueWriter.accept(this, entry.getValue());
        }
    }

    /**
     * 写入 nullable 字段 presence bitmap。
     *
     * @param values 字段是否存在的布尔数组；不可为空。
     * @throws NullPointerException 当布尔数组为空时抛出。
     */
    public void writePresenceBits(final boolean... values) {
        Objects.requireNonNull(values, "values");
        writeUnsignedInt(values.length);
        int byteCount = (values.length + Byte.SIZE - 1) / Byte.SIZE;
        for (int byteIndex = 0; byteIndex < byteCount; byteIndex++) {
            int word = 0;
            int base = byteIndex * Byte.SIZE;
            int end = Math.min(values.length, base + Byte.SIZE);
            for (int bitIndex = base; bitIndex < end; bitIndex++) {
                if (values[bitIndex]) {
                    word |= 1 << (bitIndex - base);
                }
            }
            writeByte(word);
        }
    }

    /**
     * 写入 nullable 字段 presence bitmap。
     *
     * @param fieldCount 字段数量。
     * @param presentPredicate 字段存在判断器；不可为空。
     * @throws NullPointerException 当判断器为空时抛出。
     * @throws IllegalArgumentException 当字段数量非法时抛出。
     */
    public void writePresenceBits(final int fieldCount, final IntPredicate presentPredicate) {
        Objects.requireNonNull(presentPredicate, "presentPredicate");
        if (fieldCount < 0) {
            throw new IllegalArgumentException("fieldCount must not be negative");
        }
        writeUnsignedInt(fieldCount);
        int byteCount = (fieldCount + Byte.SIZE - 1) / Byte.SIZE;
        for (int byteIndex = 0; byteIndex < byteCount; byteIndex++) {
            int word = 0;
            int base = byteIndex * Byte.SIZE;
            int end = Math.min(fieldCount, base + Byte.SIZE);
            for (int bitIndex = base; bitIndex < end; bitIndex++) {
                if (presentPredicate.test(bitIndex)) {
                    word |= 1 << (bitIndex - base);
                }
            }
            writeByte(word);
        }
    }

    /**
     * 开始写入对象体。
     *
     * <p>对象体前缀会在 {@link #endObject(int)} 中回填为 Unsigned VarInt 长度。
     *
     * @return 对象标记；必须传回 endObject；线程不安全。
     */
    public int beginObject() {
        int marker = writerIndex;
        ensureWritable(MAX_INT_BYTES);
        writerIndex += MAX_INT_BYTES;
        return marker;
    }

    /**
     * 结束对象体并回填对象长度。
     *
     * @param marker beginObject 返回的对象标记。
     * @throws IllegalArgumentException 当对象标记非法时抛出。
     */
    public void endObject(final int marker) {
        if (marker < 0 || marker + MAX_INT_BYTES > writerIndex) {
            throw new IllegalArgumentException("invalid object marker");
        }
        int contentStart = marker + MAX_INT_BYTES;
        int length = writerIndex - contentStart;
        int lengthBytes = unsignedIntSize(length);
        int shrink = MAX_INT_BYTES - lengthBytes;
        if (shrink > 0 && length > 0) {
            buffer.copy(contentStart, marker + lengthBytes, length);
        }
        writerIndex -= shrink;
        writeUnsignedIntAt(marker, length);
    }

    /**
     * 释放底层资源。
     */
    @Override
    public void close() {
        buffer.close();
    }

    /**
     * 写入固定 4 字节大端整数。
     *
     * @param value 待写入值。
     */
    private void writeFixedInt(final int value) {
        ensureWritable(Integer.BYTES);
        buffer.putByte(writerIndex++, (byte) (value >>> 24));
        buffer.putByte(writerIndex++, (byte) (value >>> 16));
        buffer.putByte(writerIndex++, (byte) (value >>> 8));
        buffer.putByte(writerIndex++, (byte) value);
    }

    /**
     * 写入固定 8 字节大端 long。
     *
     * @param value 待写入值。
     */
    private void writeFixedLong(final long value) {
        ensureWritable(Long.BYTES);
        for (int shift = 56; shift >= 0; shift -= 8) {
            buffer.putByte(writerIndex++, (byte) (value >>> shift));
        }
    }

    /**
     * 写入原始 VarInt32。
     *
     * @param value 待写入值。
     */
    private void writeRawVarInt32(final int value) {
        ensureWritable(unsignedIntSize(value));
        writerIndex += writeUnsignedIntAt(writerIndex, value);
    }

    /**
     * 写入原始 VarInt64。
     *
     * @param value 待写入值。
     */
    private void writeRawVarInt64(final long value) {
        ensureWritable(10);
        long remaining = value;
        while ((remaining & ~0x7FL) != 0L) {
            buffer.putByte(writerIndex++, (byte) (((int) remaining & 0x7F) | 0x80));
            remaining >>>= 7;
        }
        buffer.putByte(writerIndex++, (byte) remaining);
    }

    /**
     * 在指定位置写入 unsigned int。
     *
     * @param index 写入位置。
     * @param value 待写入值。
     * @return 实际写入字节数。
     */
    private int writeUnsignedIntAt(final int index, final int value) {
        int current = index;
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            buffer.putByte(current++, (byte) ((remaining & 0x7F) | 0x80));
            remaining >>>= 7;
        }
        buffer.putByte(current++, (byte) remaining);
        return current - index;
    }

    /**
     * 计算 unsigned int 需要的字节数。
     *
     * @param value 待计算值。
     * @return 字节数。
     */
    private int unsignedIntSize(final int value) {
        if ((value & ~0x7F) == 0) {
            return 1;
        }
        if ((value & ~0x3FFF) == 0) {
            return 2;
        }
        if ((value & ~0x1F_FFFF) == 0) {
            return 3;
        }
        if ((value & ~0x0FFF_FFFF) == 0) {
            return 4;
        }
        return 5;
    }

    /**
     * 写入 nullable size。
     *
     * @param size 数量。
     * @throws IllegalArgumentException 当数量非法时抛出。
     */
    private void writeNullableSize(final int size) {
        if (size < 0 || size == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("nullable size is out of range");
        }
        writeUnsignedInt(size + 1);
    }

    /**
     * 确保可写空间足够。
     *
     * @param length 需要的字节数。
     * @throws IllegalArgumentException 当长度为负数时抛出。
     */
    private void ensureWritable(final int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must not be negative");
        }
        int required = writerIndex + length;
        if (required < writerIndex) {
            throw new IllegalArgumentException("required length overflow");
        }
        buffer.ensureCapacity(required);
    }
}
