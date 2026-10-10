package group.zn.zero.protocol.buffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import group.zn.zero.core.error.ZeroException;
import org.junit.jupiter.api.Test;

/** 只展开 schema 已知位；未知位图必须完整跳过且受对象边界约束。 @author zn */
class PresenceSchemaBoundaryTest {
    /** 16 MiB 内的合法位图曾生成约 128 MiB 数组；现在只产生三个已知位。 */
    @Test void largeValidBitmapAllocatesOnlyKnownFields() {
        int bitmapBytes = 16 * 1024 * 1024 - 8;
        byte[] payload = new byte[bitmapBytes + 5];
        payload[0] = (byte) 0xc0;
        payload[1] = (byte) 0xff;
        payload[2] = (byte) 0xff;
        payload[3] = 0x3f;
        payload[4] = 5;
        payload[payload.length - 1] = 42;
        var reader = new ZeroReader(payload);
        assertArrayEquals(new boolean[] {true, false, true}, reader.readPresenceBits(3));
        assertEquals(42, reader.readByte());
    }

    /** 已知位少于一字节时也须跳过完整未知位图；下一字段不能错位。 */
    @Test void unknownNullableFieldsPreserveObjectAndFollowingFieldBoundaries() {
        try (var writer = new ZeroWriter()) {
            int marker = writer.beginObject();
            writer.writePresenceBits(17, index -> index == 0 || index == 8 || index == 16);
            writer.writeInt(42);
            writer.endObject(marker);
            writer.writeByte((byte) 7);
            var reader = new ZeroReader(writer.toByteArray());
            int end = reader.beginObject();
            assertArrayEquals(new boolean[] {true, false}, reader.readPresenceBits(2));
            assertEquals(42, reader.readInt());
            reader.endObject(end);
            assertEquals(7, reader.readByte());
        }
    }

    /** 不能用对象外字节补足位图，也不能将较短旧 schema 扩张成新 schema 大小。 */
    @Test void rejectsTruncatedObjectAndSupportsEmptyOrOlderSchemas() {
        var reader = new ZeroReader(new byte[] {2, 17, 1, 0, 0});
        reader.beginObject();
        assertThrows(ZeroException.class, () -> reader.readPresenceBits(1));
        assertArrayEquals(new boolean[] {true}, new ZeroReader(new byte[] {1, 1}).readPresenceBits(10));
        assertEquals(0, new ZeroReader(new byte[] {0}).readPresenceBits(10).length);
        var skipped = new ZeroReader(new byte[] {9, 1, 1, 42});
        assertEquals(0, skipped.readPresenceBits(0).length);
        assertEquals(42, skipped.readByte());
        assertThrows(IllegalArgumentException.class, () -> new ZeroReader(new byte[0]).readPresenceBits(-1));
    }
}
