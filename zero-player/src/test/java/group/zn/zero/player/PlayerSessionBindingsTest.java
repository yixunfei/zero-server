package group.zn.zero.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/** 清理预算与身份代际的确定性状态转换回归。 @author zn */
class PlayerSessionBindingsTest {
    @Test void capacityRejectsBeforeUnbindingAndRecoveryReleasesBudget() {
        var bindings = new PlayerSessionBindings(1);
        bindings.login("a", 1, "s1");
        bindings.login("b", 2, "s2");
        var first = bindings.logout(request("a", 1, "s1"));
        var failure = assertThrows(ZeroException.class, () -> bindings.logout(request("b", 2, "s2")));
        assertEquals(PlayerErrorCode.CLEANUP_CAPACITY_EXCEEDED, failure.errorCode());
        assertEquals(2L, bindings.find("b").orElseThrow());
        bindings.rejected(first);
        assertNull(bindings.logout(request("a", 1, "wrong")));
        assertNull(bindings.logout(request("wrong", 1, "s1")));
        assertNull(bindings.logout(request("a", 2, "s1")));
        var retry = bindings.logout(request("a", 1, "s1"));
        assertTrue(retry.submit());
        var removed = new ArrayList<Long>();
        bindings.clean(first, removed::add);
        assertTrue(removed.isEmpty());
        bindings.clean(retry, removed::add);
        assertEquals(java.util.List.of(1L), removed);
        assertNotNull(bindings.logout(request("b", 2, "s2")));
    }

    @Test void replacementInvalidatesOldCleanupEvenAfterReplacementLogsOut() {
        var bindings = new PlayerSessionBindings(1);
        bindings.login("a", 1, "old");
        var old = bindings.logout(request("a", 1, "old"));
        bindings.login("b", 1, "new");
        var current = bindings.logout(request("b", 1, "new"));
        var removed = new ArrayList<Long>();
        bindings.clean(old, removed::add);
        bindings.rejected(old);
        assertTrue(removed.isEmpty());
        assertNull(bindings.logout(request("a", 1, "old")));
        var duplicate = bindings.logout(request("b", 1, "new"));
        assertFalse(duplicate.submit());
        assertSame(current.result(), duplicate.result());
        bindings.clean(current, removed::add);
        assertEquals(java.util.List.of(1L), removed);
        assertNull(bindings.logout(request("b", 1, "new")));
    }

    @Test void retryOldUidDoesNotRemoveReusedAccountBinding() {
        var bindings = new PlayerSessionBindings(2);
        bindings.login("a", 1, "old");
        var old = bindings.logout(request("a", 1, "old"));
        bindings.rejected(old);
        bindings.login("a", 2, "new");
        bindings.clean(bindings.logout(request("a", 1, "old")), uid -> assertEquals(1, uid));
        assertEquals(2L, bindings.find("a").orElseThrow());
    }

    private static PlayerLogoutRequest request(final String account, final long uid, final String session) {
        return new PlayerLogoutRequest(account, uid, session, "trace");
    }
}
