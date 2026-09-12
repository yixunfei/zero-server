package group.zn.zero.examples.room;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class RoomGameApplicationTest {
    @Test
    void printsStableLocalSummary() {
        String summary = RoomGameApplication.runDemo();
        assertTrue(summary.contains("room-game=ok"));
        assertTrue(summary.contains("disconnect,reconnect,settle,close"));
        assertTrue(summary.contains("productionReady=false"));
    }
}
