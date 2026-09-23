package group.zn.zero.protocol.buffer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import group.zn.zero.core.error.ZeroException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** 畸形长度必须在按长度分配之前拒绝。 @author zn */
class ReaderReportAuditTest {
    /** 最大合法整数长度但无载荷，必须返回协议错误。 */
    @Test void malformedBitmapDoesNotAllocateFromUntrustedCount() {
        byte[] payload = {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 7};
        assertThrows(ZeroException.class, () -> new ZeroReader(payload).readPresenceBits());
        assertThrows(ZeroException.class, () -> new ZeroReader(payload).readIntArray());
    }
    /** 非法集合在工厂拿到攻击者容量前拒绝。 */
    @Test void truncatedCollectionDoesNotInvokeCapacityFactory() {
        AtomicBoolean called = new AtomicBoolean();
        assertThrows(ZeroException.class, () -> new ZeroReader(new byte[] {64}).readCollection(count -> {
            called.set(true);
            return new ArrayList<>(count);
        }, ZeroReader::readInt));
        assertFalse(called.get());
    }
    /** 溢出的 nullable sizePlusOne 不得被当作 null。 */
    @Test void unsignedOverflowCannotMasqueradeAsNull() {
        byte[] payload = {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 15};
        assertThrows(ZeroException.class, () -> new ZeroReader(payload).readNullableBooleanArray());
        assertThrows(ZeroException.class, () -> new ZeroReader(payload).readNullableList(ZeroReader::readInt));
    }

}
