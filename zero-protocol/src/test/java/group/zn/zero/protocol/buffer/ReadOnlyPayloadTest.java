package group.zn.zero.protocol.buffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.protocol.ProtocolFrame;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** 只读存储不能通过 reader/slice 间接修改；批量重叠搬移对照数组模型。 @author zn */
class ReadOnlyPayloadTest {
    /** heap/direct 输入只读取 remaining，保留独立游标并禁止所有可写逃逸。 */
    @Test void borrowedViewsCannotExposeWritableStorage() {
        for (boolean direct : new boolean[] {false, true}) {
            ByteBuffer source = direct ? ByteBuffer.allocateDirect(8) : ByteBuffer.allocate(8);
            source.put(new byte[] {9, 1, 2, 3, 8}).flip().position(1).limit(4);
            ZeroBuffer buffer = new ReadOnlyZeroBuffer(source);
            assertFalse(new ZeroReader(source.asReadOnlyBuffer()).readBytesView(3).hasArray());
            assertEquals(1, source.position());
            assertFalse(buffer.hasArray());
            assertThrows(UnsupportedOperationException.class, buffer::array);
            assertThrows(ReadOnlyBufferException.class, () -> buffer.putByte(0, (byte) 7));
            assertThrows(ReadOnlyBufferException.class, () -> buffer.putBytes(0, new byte[0], 0, 0));
            assertThrows(ReadOnlyBufferException.class, () -> buffer.putBytes(0, ByteBuffer.allocate(0)));
            assertThrows(ReadOnlyBufferException.class, () -> buffer.ensureCapacity(32));
            assertThrows(ReadOnlyBufferException.class, () -> buffer.copy(0, 1, 1));
            assertThrows(ReadOnlyBufferException.class, () -> buffer.toByteBuffer(0, 3).put(0, (byte) 4));
            source.put(1, (byte) 5);
            assertEquals(5, buffer.getByte(0));
            assertThrows(IndexOutOfBoundsException.class, () -> buffer.getByte(3));
        }
    }
    /** 帧提供自持有稳定数据，来源数组修改及返回副本修改均不影响已保留 reader。 */
    @Test void frameReaderSurvivesCallerMutation() {
        byte[] input = {1, 2, 3};
        ProtocolFrame frame = new ProtocolFrame(1, 1, 0, null, input);
        var reader = frame.payloadReader();
        assertFalse(frame.payloadReader().readBytesView(3).hasArray());
        assertThrows(UnsupportedOperationException.class, () -> frame.payloadReader().readBytesView(3).array());
        assertThrows(ReadOnlyBufferException.class,
                () -> frame.payloadReader().readBytesView(3).toByteBuffer().put(0, (byte) 8));
        input[0] = 7;
        frame.payload()[1] = 8;
        assertArrayEquals(new byte[] {1, 2, 3}, reader.readBytes(3));
        assertTrue(frame.payloadView().isReadOnly());
    }
    /** 自定义 Charset 的 decoder 不得取得 Frame 的私有数组引用。 */
    @Test void untrustedCharsetCannotMutateFrameStorage() {
        var frame = new ProtocolFrame(1, 1, 0, null, new byte[] {65});
        var charset = new java.nio.charset.Charset("test-mutable-decoder", null) {
            @Override public boolean contains(final java.nio.charset.Charset other) { return false; }
            @Override public java.nio.charset.CharsetEncoder newEncoder() { throw new UnsupportedOperationException(); }
            @Override public java.nio.charset.CharsetDecoder newDecoder() {
                return new java.nio.charset.CharsetDecoder(this, 1, 1) {
                    @Override protected java.nio.charset.CoderResult decodeLoop(final ByteBuffer input,
                            final java.nio.CharBuffer output) {
                        if (!input.hasRemaining()) return java.nio.charset.CoderResult.UNDERFLOW;
                        if (!output.hasRemaining()) return java.nio.charset.CoderResult.OVERFLOW;
                        if (input.hasArray()) input.array()[input.arrayOffset() + input.position()] = 66;
                        input.get();
                        output.put('B');
                        return java.nio.charset.CoderResult.UNDERFLOW;
                    }
                };
            }
        };
        assertEquals("B", frame.payloadReader().readBytesView(1).decodeString(charset));
        assertArrayEquals(new byte[] {65}, frame.payload());
    }

    /** 随机双向重叠、相同区间、扩容后范围与 slice 边界对照 System.arraycopy。 */
    @Test void directMoveMatchesArrayModel() {
        Random random = new Random(1777);
        ByteBuffer input = ByteBuffer.allocateDirect(300).position(10).limit(266);
        try (ZeroBuffer buffer = new DirectZeroBuffer(input)) {
            for (int step = 0; step < 1000; step++) {
                byte[] model = new byte[buffer.capacity()];
                random.nextBytes(model);
                buffer.putBytes(0, model, 0, model.length);
                int length = random.nextInt(model.length + 1);
                int from = random.nextInt(model.length - length + 1);
                int to = random.nextInt(model.length - length + 1);
                System.arraycopy(model, from, model, to, length);
                buffer.copy(from, to, length);
                assertArrayEquals(model, buffer.toByteArray(0, model.length));
                if (step == 500) buffer.ensureCapacity(512);
            }
            assertThrows(IndexOutOfBoundsException.class, () -> buffer.copy(0, 512, 1));
        }
    }
}
