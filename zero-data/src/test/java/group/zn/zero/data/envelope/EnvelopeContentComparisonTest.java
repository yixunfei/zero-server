package group.zn.zero.data.envelope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.data.persistence.durable.DurableRecord;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** 耐久意图比较包含全部元数据及 payload，保持存储字节语义。 @author zn */
class EnvelopeContentComparisonTest {
    @Test void everyFieldAndEveryPayloadByteParticipatesInComparison() {
        var original = envelope(-1);
        assertTrue(original.contentEquals(envelope(-1)));
        assertFalse(original.contentEquals(null));
        var codec = new ZeroDataEnvelopeCodec();
        for (int changed = -1; changed < 8; changed++) {
            var other = envelope(changed);
            assertEquals(changed == -1, DurableRecord.sameEnvelope(original, other));
            assertEquals(Arrays.equals(codec.encode(original), codec.encode(other)), original.contentEquals(other));
        }
        byte[] copy = original.payload();
        copy[0] = 99;
        assertTrue(original.contentEquals(envelope(-1)));
    }

    private static ZeroDataEnvelope envelope(final int changed) {
        return new ZeroDataEnvelope(changed == 0 ? "other" : "ns", changed == 1 ? "other" : "collection",
                changed == 2 ? "other" : "id", changed == 3 ? 2 : 1, changed == 4 ? 2 : 1,
                changed == 5 ? 2 : 1, changed == 6 ? 2 : 1, new byte[]{1, (byte) (changed == 7 ? 3 : 2)});
    }
}
