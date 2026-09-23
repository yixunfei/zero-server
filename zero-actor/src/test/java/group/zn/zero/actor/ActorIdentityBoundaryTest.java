package group.zn.zero.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 36 进制边界与耗尽失败，保护默认消息和上下文的关联身份。 @author zn */
class ActorIdentityBoundaryTest {
    @Test void radixTransitionsAndExhaustionPreserveIdentityContract() throws Exception {
        var field = ActorMessageIds.class.getDeclaredField("SEQUENCE");
        field.setAccessible(true);
        AtomicLong counter = (AtomicLong) field.get(null);
        long prior = counter.get();
        try {
            for (long value : new long[]{1, 35, 36, 1295, 1296, Long.MAX_VALUE}) {
                counter.set(value - 1);
                var message = new ActorMessage(LaneKey.custom("boundary"), "payload");
                String id = message.messageId();
                assertEquals(value, Long.parseLong(id.substring(id.lastIndexOf('-') + 1), 36));
                assertSame(id, message.traceId());
                assertSame(id, ActorContext.from(message).messageId());
            }
            assertThrows(IllegalStateException.class, () -> new ActorMessage(LaneKey.custom("boundary"), "payload"));
        } finally { counter.set(prior); }
    }
}
