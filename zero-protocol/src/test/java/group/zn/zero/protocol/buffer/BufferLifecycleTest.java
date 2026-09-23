package group.zn.zero.protocol.buffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** direct 绝对读写和 native 独占生命周期测试。 @author zn */
class BufferLifecycleTest {
    /** native 扩容保留数据，双向重叠复制正确，close 幂等且借用切片失效。 */
    @Test
    void nativeGrowthCopyAndCloseRespectOwnership() {
        assumeTrue(ZeroBuffers.nativeMemoryAvailable());
        var buffer = new NativeMemoryZeroBuffer(8);
        try {
            byte[] expected = {1, 2, 3, 4, 5, 6, 7, 8};
            buffer.putBytes(0, expected, 0, expected.length);
            buffer.ensureCapacity(32);
            assertArrayEquals(expected, buffer.toByteArray(0, 8));
            System.arraycopy(expected, 0, expected, 2, 6);
            buffer.copy(0, 2, 6);
            assertArrayEquals(expected, buffer.toByteArray(0, 8));
            System.arraycopy(expected, 2, expected, 0, 6);
            buffer.copy(2, 0, 6);
            assertArrayEquals(expected, buffer.toByteArray(0, 8));
            buffer.copy(0, 16, 8);
            assertArrayEquals(expected, buffer.toByteArray(16, 8));
            ZeroBufferSlice slice = new ZeroBufferSlice(buffer, 0, 8);
            ByteBuffer copy = buffer.toByteBuffer(0, 8);
            assertThrows(IndexOutOfBoundsException.class, () -> buffer.getByte(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> buffer.putBytes(31, expected, 0, 8));
            buffer.close();
            buffer.close();
            assertThrows(IllegalStateException.class, buffer::capacity);
            assertThrows(IllegalStateException.class, () -> slice.getByte(0));
            assertThrows(IllegalStateException.class, () -> buffer.ensureCapacity(64));
            byte[] independent = new byte[8];
            copy.get(independent);
            assertArrayEquals(expected, independent);
        } finally {
            buffer.close();
        }
    }

    /** 绝对批量读写不改变原 ByteBuffer 游标，借用偏移、只读限制与越界仍正确。 */
    @Test
    void directBulkOperationsRespectSliceOffsetsAndReadOnlyBuffers() {
        ByteBuffer source = ByteBuffer.allocateDirect(20);
        source.position(4).limit(16);
        var buffer = new DirectZeroBuffer(source);
        byte[] bytes = {9, 1, 2, 3, 8};
        buffer.putBytes(2, bytes, 1, 3);
        byte[] result = new byte[5];
        buffer.getBytes(2, result, 1, 3);
        assertArrayEquals(new byte[] {0, 1, 2, 3, 0}, result);
        assertEquals(4, source.position());
        assertEquals(16, source.limit());
        assertEquals(1, source.get(6));
        buffer.ensureCapacity(32);
        assertArrayEquals(Arrays.copyOfRange(bytes, 1, 4), buffer.toByteArray(2, 3));
        assertThrows(IndexOutOfBoundsException.class, () -> buffer.getBytes(31, result, 0, 3));
        var readOnly = new DirectZeroBuffer(source.asReadOnlyBuffer());
        assertThrows(ReadOnlyBufferException.class, () -> readOnly.putBytes(0, bytes, 0, 1));
        assertEquals(1, readOnly.getByte(2));
    }
}
