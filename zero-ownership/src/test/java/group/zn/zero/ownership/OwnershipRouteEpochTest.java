package group.zn.zero.ownership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 过期只影响路由可用性，不应丢失已知 epoch 的栅栏。 @author zn */
class OwnershipRouteEpochTest {
    /** 旧 epoch 和同代其他 owner 不可覆盖，续租和到期后的高代接管仍合法。 */
    @Test void expirationDoesNotPermitEpochRollback() {
        var clock = new RouteClock();
        var router = new InMemoryOwnershipRouter(clock);
        var resource = new OwnershipResource("entity", "one", 1);
        var owner = new ProcessIncarnation("node", "one");
        var other = new ProcessIncarnation("other", "one");
        router.publish(new OwnershipLease(resource, owner, 5, clock.instant().plusSeconds(1)), "rpc://one");
        var old = new OwnershipLease(resource, owner, 2, clock.instant().plusSeconds(100));
        assertThrows(OwnershipException.class, () -> router.publish(old, "rpc://old"));
        clock.now = clock.now.plusSeconds(2);
        assertThrows(OwnershipException.class, () -> router.publish(old, "rpc://old"));
        assertThrows(OwnershipException.class, () -> router.publish(
                new OwnershipLease(resource, other, 5, clock.instant().plusSeconds(1)), "rpc://other"));
        var renewed = new OwnershipLease(resource, owner, 5, clock.instant().plusSeconds(1));
        assertEquals(renewed.expiresAt(), router.publish(renewed, "rpc://renewed").expiresAt());
        clock.now = clock.now.plusSeconds(2);
        router.publish(new OwnershipLease(resource, other, 6, clock.instant().plusSeconds(1)), "rpc://other");
        assertEquals(6, router.resolve(resource).orElseThrow().epoch());
    }

    /** 测试独占可控时钟。 @author zn */
    private static final class RouteClock extends Clock {
        /** 当前时间，仅测试线程修改。 */
        private Instant now = Instant.parse("2026-10-07T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
