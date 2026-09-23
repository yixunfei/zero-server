package group.zn.zero.protocol.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.protocol.ProtocolDefinition;
import group.zn.zero.protocol.ProtocolDirection;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 同步编码缓冲复用的所有权、异常、嵌套及并发回归。 @author zn */
class EncodingReuseTest {
    /** 测试协议定义。 */
    private static final ProtocolDefinition DEFINITION =
            new ProtocolDefinition(1, "reuse", ProtocolDirection.values()[0], 1);

    /** 嵌套 encode 不能覆盖外层 writer；异常不能污染下一次编码。 */
    @Test
    void nestedEncodingAndFailureDoNotOverwriteBorrowedState() {
        var inner = new GeneratedProtocolCodec<>(new BytesCodec());
        var outer = new GeneratedProtocolCodec<>(new BytesCodec() {
            @Override public void write(final ZeroWriter writer, final byte[] message) {
                writer.writeByte(11);
                byte[] nested = inner.encode(DEFINITION, message);
                writer.writeBytes(nested);
                writer.writeByte(12);
                if (message.length == 0) throw new IllegalStateException("encode failed after write");
            }
        });
        assertThrows(IllegalStateException.class, () -> outer.encode(DEFINITION, new byte[0]));
        byte[] first = outer.encode(DEFINITION, new byte[] {1, 2, 3});
        outer.encode(DEFINITION, new byte[] {9});
        assertArrayEquals(new byte[] {11, 3, 1, 2, 3, 12}, first);
        assertArrayEquals(new byte[] {1, 5}, inner.encode(DEFINITION, new byte[] {5}));
    }

    /** 包长度切换与同实例并发编码均保持返回数据独立，覆盖扩容超过保留上限。 */
    @Test
    void concurrentCodecsReturnIndependentBytesAcrossPayloadSizes() throws Exception {
        var generated = new GeneratedProtocolCodec<>(new BytesCodec());
        var frameCodec = new ZeroBinaryFrameCodec();
        var executor = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int id = 0; id < 8; id++) {
                final byte value = (byte) id;
                futures.add(executor.submit(() -> {
                    for (int size : new int[] {1, 65_536, 3, 131_072, 0, 1024}) {
                        byte[] message = new byte[size];
                        Arrays.fill(message, value);
                        byte[] encoded = generated.encode(DEFINITION, message);
                        generated.encode(DEFINITION, new byte[] {99});
                        assertArrayEquals(message, generated.decode(DEFINITION, encoded, byte[].class));
                        byte[] frame = frameCodec.encode(new ProtocolFrame(1, 1, 0, null, encoded));
                        frameCodec.encode(new ProtocolFrame(2, 1, 0, null, new byte[] {4}));
                        assertArrayEquals(encoded, frameCodec.decode(frame).payload());
                    }
                }));
            }
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** 保留预算是资源契约：扩容后的大缓冲不得留存，小缓冲可复用，虚拟线程不缓存。 */
    @Test
    void retentionIsBoundedAndVirtualThreadsDoNotCache() throws Exception {
        var platform = Executors.newSingleThreadExecutor();
        try {
            platform.submit(() -> {
                ZeroWriter small = EncodingWriters.acquire(128);
                small.writeByte(1);
                EncodingWriters.release(small);
                ZeroWriter reused = EncodingWriters.acquire(64);
                assertSame(small, reused);
                assertEquals(0, reused.writerIndex());
                reused.writeBytes(new byte[131_072]);
                EncodingWriters.release(reused);
                ZeroWriter afterLarge = EncodingWriters.acquire(16);
                assertNotSame(reused, afterLarge);
                assertEquals(16, afterLarge.capacity());
                EncodingWriters.release(afterLarge);
            }).get(5, TimeUnit.SECONDS);
        } finally {
            platform.shutdownNow();
        }
        try (var virtual = Executors.newVirtualThreadPerTaskExecutor()) {
            virtual.submit(() -> {
                ZeroWriter first = EncodingWriters.acquire(16);
                EncodingWriters.release(first);
                ZeroWriter second = EncodingWriters.acquire(16);
                assertNotSame(first, second);
                EncodingWriters.release(second);
            }).get(5, TimeUnit.SECONDS);
        }
    }

    /** 默认帧编码保持既有 wire format，包括最大 varint 字段。 */
    @Test
    void frameEncodingRetainsGoldenBytes() {
        var codec = new ZeroBinaryFrameCodec();
        var frame = new ProtocolFrame(1, 1, 0, new byte[] {8}, new byte[] {9, 10});
        assertArrayEquals(new byte[] {'Z', 'R', 'O', '1', 1, 1, 0, 1, 1, 8, 2, 9, 10}, codec.encode(frame));
        var maximal = new ProtocolFrame(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, null, new byte[0]);
        var decoded = codec.decode(codec.encode(maximal));
        assertEquals(Integer.MAX_VALUE, decoded.protocolId());
        assertEquals(Integer.MAX_VALUE, decoded.flags());
        assertEquals(Integer.MAX_VALUE, decoded.protocolVersion());
    }

    /** 故意低估尺寸以覆盖编码时扩容。 @author zn */
    private static class BytesCodec implements ZeroPayloadCodec<byte[]> {
        /** @return 测试提供者名；线程安全。 */
        @Override public String name() { return "reuse-test"; }
        /** @return 消息类型；线程安全。 */
        @Override public Class<byte[]> messageType() { return byte[].class; }
        /** 同步借用 writer 写入 bytes；不保留视图，线程安全性由 writer 独占保证。 */
        @Override public void write(final ZeroWriter writer, final byte[] message) { writer.writeByteArray(message); }
        /** @return bytes 副本，可变有序，可能为空；独占 reader。 */
        @Override public byte[] read(final ZeroReader reader) { return reader.readByteArray(); }
        /** @return 初始容量；线程安全。 */
        @Override public int estimatedSize(final byte[] message) { return 16; }
    }
}
