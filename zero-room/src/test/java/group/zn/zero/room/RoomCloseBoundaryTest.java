package group.zn.zero.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** CLOSED 冻结业务状态，保留幂等 close 与销毁入口。 @author zn */
class RoomCloseBoundaryTest {
    /** 所有可改变成员的命令均不能在 close 后发事件。 */
    @Test void closedRoomRejectsMemberChanges() {
        var service = new LocalRoomService();
        var id = new RoomId("closed");
        service.create(id, 2, 100);
        service.join(id, "p").toCompletableFuture().join();
        service.close(id).toCompletableFuture().join();
        var snapshot = service.snapshot(id);
        var stats = service.stats(id);
        var events = service.events(id);
        List<Supplier<CompletionStage<Void>>> commands = List.of(
                () -> service.leave(id, "p"), () -> service.disconnect(id, "p", 1),
                () -> service.reconnect(id, "p", 2), () -> service.ready(id, "p"));
        for (var command : commands) {
            assertThrows(RuntimeException.class, () -> command.get().toCompletableFuture().join());
            assertEquals(snapshot, service.snapshot(id));
            assertEquals(stats, service.stats(id));
            assertEquals(events, service.events(id));
        }
        service.close(id).toCompletableFuture().join();
        service.destroy(id).toCompletableFuture().join();
        assertThrows(RuntimeException.class, () -> service.snapshot(id));
    }
}
