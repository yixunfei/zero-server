package group.zn.zero.protocol.buffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * zero 二进制读写器测试。
 *
 * @author zn
 */
class ZeroBufferTest {

    /**
     * 验证基础类型可以往返编解码。
     */
    @Test
    void primitiveValuesShouldRoundTrip() {
        ZeroWriter writer = new ZeroWriter();

        writer.writeBoolean(true);
        writer.writeByte(7);
        writer.writeShort((short) -12);
        writer.writeInt(Integer.MIN_VALUE);
        writer.writeInt(Integer.MAX_VALUE);
        writer.writeInt(-123456);
        writer.writeUnsignedInt(123456);
        writer.writeLong(Long.MIN_VALUE);
        writer.writeLong(Long.MAX_VALUE);
        writer.writeLong(-9876543210L);
        writer.writeUnsignedLong(9876543210L);
        writer.writeFloat(1.5F);
        writer.writeDouble(-2.25D);
        writer.writeString("zero");
        writer.writeNullableString(null);
        writer.writeNullableString("");
        writer.writeNullableString("nullable");

        ZeroReader reader = new ZeroReader(writer.toByteArray());

        assertEquals(true, reader.readBoolean());
        assertEquals(7, reader.readByte());
        assertEquals(-12, reader.readShort());
        assertEquals(Integer.MIN_VALUE, reader.readInt());
        assertEquals(Integer.MAX_VALUE, reader.readInt());
        assertEquals(-123456, reader.readInt());
        assertEquals(123456, reader.readUnsignedInt());
        assertEquals(Long.MIN_VALUE, reader.readLong());
        assertEquals(Long.MAX_VALUE, reader.readLong());
        assertEquals(-9876543210L, reader.readLong());
        assertEquals(9876543210L, reader.readUnsignedLong());
        assertEquals(1.5F, reader.readFloat());
        assertEquals(-2.25D, reader.readDouble());
        assertEquals("zero", reader.readString());
        assertNull(reader.readNullableString());
        assertEquals("", reader.readNullableString());
        assertEquals("nullable", reader.readNullableString());
        assertEquals(0, reader.readableBytes());
    }

    /**
     * 验证对象长度前缀允许旧读者跳过尾部新增字段。
     */
    @Test
    void objectLengthShouldSkipTailFields() {
        ZeroWriter writer = new ZeroWriter();
        int marker = writer.beginObject();
        writer.writeInt(101);
        writer.writeString("known");
        writer.writeString("new-tail-field");
        writer.endObject(marker);

        ZeroReader reader = new ZeroReader(writer.toByteArray());
        int objectEnd = reader.beginObject();

        assertEquals(101, reader.readInt());
        assertEquals("known", reader.readString());

        reader.endObject(objectEnd);
        assertEquals(0, reader.readableBytes());
    }

    /**
     * 验证原始数组可以往返编解码。
     */
    @Test
    void primitiveArraysShouldRoundTrip() {
        ZeroWriter writer = new ZeroWriter();

        writer.writeBooleanArray(new boolean[] {true, false, true});
        writer.writeShortArray(new short[] {-1, 0, 1});
        writer.writeIntArray(new int[] {Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE});
        writer.writeLongArray(new long[] {Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE});
        writer.writeFloatArray(new float[] {1.5F, -2.5F});
        writer.writeDoubleArray(new double[] {1.25D, -2.75D});

        ZeroReader reader = new ZeroReader(writer.toByteArray());

        assertArrayEquals(new boolean[] {true, false, true}, reader.readBooleanArray());
        assertArrayEquals(new short[] {-1, 0, 1}, reader.readShortArray());
        assertArrayEquals(new int[] {Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE}, reader.readIntArray());
        assertArrayEquals(new long[] {Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE}, reader.readLongArray());
        assertArrayEquals(new float[] {1.5F, -2.5F}, reader.readFloatArray());
        assertArrayEquals(new double[] {1.25D, -2.75D}, reader.readDoubleArray());
        assertEquals(0, reader.readableBytes());
    }

    /**
     * 验证 nullable 数组可以区分 null 与 empty。
     */
    @Test
    void nullableArraysShouldDistinguishNullAndEmpty() {
        ZeroWriter writer = new ZeroWriter();

        writer.writeNullableIntArray(null);
        writer.writeNullableIntArray(new int[0]);
        writer.writeNullableIntArray(new int[] {1, -2, 3});

        ZeroReader reader = new ZeroReader(writer.toByteArray());

        assertNull(reader.readNullableIntArray());
        assertArrayEquals(new int[0], reader.readNullableIntArray());
        assertArrayEquals(new int[] {1, -2, 3}, reader.readNullableIntArray());
    }

    /**
     * 验证 required 字段写入器拒绝 null。
     */
    @Test
    void requiredWritersShouldRejectNull() {
        ZeroWriter writer = new ZeroWriter();

        assertThrows(NullPointerException.class, () -> writer.writeString(null));
        assertThrows(NullPointerException.class, () -> writer.writeIntArray(null));
        assertThrows(NullPointerException.class, () -> writer.writeCollection(null, ZeroWriter::writeString));
        assertThrows(NullPointerException.class, () -> writer.writeMap(null, ZeroWriter::writeInt, ZeroWriter::writeString));
    }

    /**
     * 验证集合和 Map 可以嵌套往返编解码。
     */
    @Test
    void nestedCollectionsShouldRoundTrip() {
        ZeroWriter writer = new ZeroWriter();
        Map<Integer, List<String>> value = Map.of(
                1, List.of("one", "uno"),
                2, List.of("two", "dos"));

        writer.writeMap(
                value,
                ZeroWriter::writeInt,
                (out, list) -> out.writeCollection(list, ZeroWriter::writeString));
        writer.writeCollection(Set.of(3, 1, 2), ZeroWriter::writeInt);

        ZeroReader reader = new ZeroReader(writer.toByteArray());
        Map<Integer, List<String>> decoded = reader.readMap(
                ZeroReader::readInt,
                in -> in.readList(ZeroReader::readString));
        Set<Integer> decodedSet = reader.readSet(ZeroReader::readInt);

        assertEquals(value, decoded);
        assertEquals(Set.of(1, 2, 3), decodedSet);
        assertEquals(0, reader.readableBytes());
    }

    /**
     * 验证多维数组按嵌套数组处理。
     */
    @Test
    void multiDimensionalArraysShouldRoundTripAsNestedArrays() {
        ZeroWriter writer = new ZeroWriter();
        int[][] values = new int[][] {{1, 2}, {}, {-3, 4, 5}};

        writer.writeArray(values, ZeroWriter::writeIntArray);

        ZeroReader reader = new ZeroReader(writer.toByteArray());
        int[][] decoded = reader.readArray(int[].class, ZeroReader::readIntArray);

        assertEquals(values.length, decoded.length);
        for (int index = 0; index < values.length; index++) {
            assertArrayEquals(values[index], decoded[index]);
        }
    }

    /**
     * 验证 presence bitmap 可以表达多个 nullable 字段。
     */
    @Test
    void presenceBitsShouldRoundTrip() {
        ZeroWriter writer = new ZeroWriter();

        writer.writePresenceBits(true, false, true, true, false, false, true, false, true);
        writer.writePresenceBits(10, index -> index % 3 == 0);

        ZeroReader reader = new ZeroReader(writer.toByteArray());

        assertArrayEquals(
                new boolean[] {true, false, true, true, false, false, true, false, true},
                reader.readPresenceBits());
        assertArrayEquals(
                new boolean[] {true, false, false, true, false, false, true, false, false, true},
                reader.readPresenceBits());
    }

    /**
     * 验证 writer 可以复用同一底层缓冲区。
     */
    @Test
    void writerShouldResetAndReuseBuffer() {
        ZeroWriter writer = new ZeroWriter(16);
        int capacity = writer.capacity();

        writer.writeInt(123);
        writer.writeString("first");
        writer.reset();
        writer.writeInt(-456);
        writer.writeString("second");

        ZeroReader reader = new ZeroReader(writer.toByteArray());
        assertEquals(-456, reader.readInt());
        assertEquals("second", reader.readString());
        assertEquals(0, reader.readableBytes());
        assertEquals(capacity, writer.capacity());
    }

    /**
     * 验证 reader 的借用切片可以避免额外复制。
     */
    @Test
    void byteArrayViewShouldExposeBorrowedSlice() {
        ZeroWriter writer = new ZeroWriter();
        writer.writeByteArray(new byte[] {10, 20, 30, 40});

        ZeroReader reader = new ZeroReader(writer.toByteArray());
        ZeroBufferSlice slice = reader.readByteArrayView();

        assertEquals(4, slice.length());
        assertArrayEquals(new byte[] {10, 20, 30, 40}, slice.toByteArray());
        assertEquals(0, reader.readableBytes());
    }

    /**
     * 验证 direct buffer 可以往返编解码。
     */
    @Test
    void directBufferShouldRoundTrip() {
        ZeroWriter writer = ZeroWriter.direct(32);
        writer.writeInt(77);
        writer.writeString("direct");
        writer.writeDouble(3.5D);

        ZeroReader reader = new ZeroReader(writer.toByteBuffer());

        assertEquals(77, reader.readInt());
        assertEquals("direct", reader.readString());
        assertEquals(3.5D, reader.readDouble());
        assertEquals(0, reader.readableBytes());
    }

    /**
     * 验证 native memory buffer 在可用时可以往返编解码。
     */
    @Test
    void nativeMemoryBufferShouldRoundTripWhenAvailable() {
        assumeTrue(ZeroBuffers.nativeMemoryAvailable());

        try (ZeroWriter writer = ZeroWriter.nativeMemory(32)) {
            writer.writeLong(9876543210L);
            writer.writeNullableString("native");

            ZeroReader reader = new ZeroReader(writer.buffer(), 0, writer.writerIndex());
            assertEquals(9876543210L, reader.readLong());
            assertEquals("native", reader.readNullableString());
            assertEquals(0, reader.readableBytes());
        }
    }

    /**
     * 验证 ByteBuffer 读取入口可以接收 direct buffer。
     */
    @Test
    void byteBufferReaderShouldAcceptDirectBuffer() {
        ZeroWriter writer = ZeroWriter.direct(16);
        writer.writeInt(900);
        ByteBuffer buffer = writer.toByteBuffer();

        ZeroReader reader = new ZeroReader(buffer);
        assertEquals(900, reader.readInt());
        assertEquals(0, reader.readableBytes());
    }
}
