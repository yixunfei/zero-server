package group.zn.zero.protocol.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.core.error.ZeroException;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** 缓冲 API 的字节格式、所有权与长度拒绝验证。 @author zn */
class DirectFramePathTest {
    @Test void viewsDoNotExposeMutableStorage() {
        byte[] source = {1, 2, 3};
        ProtocolFrame frame = new ProtocolFrame(123, 4, 0, source, source);
        source[0] = 9;
        frame.payload()[1] = 9;
        assertEquals(3, frame.payloadLength());
        assertEquals(3, frame.extensionLength());
        assertEquals(1, frame.payloadView().get(0));
        assertThrows(ReadOnlyBufferException.class, () -> frame.payloadView().put(0, (byte) 9));
        assertThrows(ReadOnlyBufferException.class, () -> frame.extensionView().array());
    }

    @Test void bufferFrameEncodingMatchesIndependentGoldenWriter() {
        var codec = new ZeroBinaryFrameCodec();
        for (int size : new int[] {0, 64, 127, 128, 16384, 131072}) {
            ProtocolFrame frame = new ProtocolFrame(65537, 128, 3, new byte[] {4, 5}, new byte[size]);
            try (ZeroWriter golden = new ZeroWriter(); ZeroWriter actual = ZeroWriter.direct(8)) {
                golden.writeBytes(new byte[] {'Z', 'R', 'O', '1'});
                for (int value : new int[] {1, 128, 3, 65537}) golden.writeUnsignedInt(value);
                golden.writeByteArray(frame.extension());
                golden.writeByteArray(frame.payload());
                codec.encodeTo(frame, actual);
                assertArrayEquals(golden.toByteArray(), actual.toByteArray());
                assertEquals(golden.writerIndex(), codec.encodedLength(frame));
                assertArrayEquals(frame.payload(), codec.decodeFrom(new ZeroReader(actual.toByteArray())).payload());
            }
        }
    }

    @Test void malformedLengthsFailBeforeReadingOversizedField() {
        try (ZeroWriter writer = new ZeroWriter()) {
            writer.writeBytes(new byte[] {'Z', 'R', 'O', '1'});
            for (int value : new int[] {1, 1, 0, 1, 0, Integer.MAX_VALUE}) writer.writeUnsignedInt(value);
            ZeroException failure = assertThrows(ZeroException.class,
                    () -> new ZeroBinaryFrameCodec(10, 10).decode(writer.toByteArray()));
            org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("payload length exceeds"));
        }
    }

    @Test void utf8MatchesJdkForEveryUtf16CodeUnitAndMixedSurrogates() {
        Random random = new Random(23);
        try (ZeroWriter heap = new ZeroWriter(); ZeroWriter direct = ZeroWriter.direct(16);
                ZeroWriter nativeWriter = ZeroWriter.nativeMemory(16)) {
            for (int i = 0; i < 65_536; i++) verifyString(Character.toString((char) i), heap);
            for (int i = 0; i < 1000; i++) {
                StringBuilder sample = new StringBuilder("ascii中文😀");
                for (int j = 0; j < random.nextInt(256); j++) sample.append((char) random.nextInt(65536));
                for (ZeroWriter writer : new ZeroWriter[] {heap, direct, nativeWriter}) verifyString(sample.toString(), writer);
            }
            verifyString("a".repeat(16383), heap);
            verifyString("a".repeat(16384), heap);
        }
    }

    private static void verifyString(final String value, final ZeroWriter writer) {
        byte[] expected = value.getBytes(StandardCharsets.UTF_8);
        writer.reset();
        writer.writeString(value);
        var reader = new ZeroReader(writer.toByteArray());
        assertEquals(expected.length, reader.readUnsignedInt());
        assertArrayEquals(expected, reader.readBytes(expected.length));
        writer.reset();
        writer.writeNullableString(value);
        reader = new ZeroReader(writer.toByteArray());
        assertEquals(expected.length + 1, reader.readUnsignedInt());
        assertArrayEquals(expected, reader.readBytes(expected.length));
    }
}
