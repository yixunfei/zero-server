package group.zn.zero.examples.room;

import group.zn.zero.room.LocalRoomService;
import group.zn.zero.room.RoomId;
import group.zn.zero.room.RoomSnapshot;
import group.zn.zero.room.Settlement;

/** Executable local room lifecycle demonstration. */
public final class RoomGameApplication {
    private RoomGameApplication() { }

    public static void main(String[] args) {
        System.out.println(runDemo());
    }

    static String runDemo() {
        LocalRoomService service = new LocalRoomService();
        RoomId room = new RoomId("room-game-1");
        RoomSnapshot created = service.create(room, 2, 5_000);
        print("created", created);
        service.join(room, "alice").toCompletableFuture().join(); print("joined alice", service.snapshot(room));
        service.join(room, "bob").toCompletableFuture().join(); print("joined bob", service.snapshot(room));
        service.ready(room, "alice").toCompletableFuture().join();
        service.ready(room, "bob").toCompletableFuture().join(); print("ready", service.snapshot(room));
        service.start(room).toCompletableFuture().join(); print("started", service.snapshot(room));
        service.disconnect(room, "bob", 1_000).toCompletableFuture().join(); print("disconnected bob", service.snapshot(room));
        service.reconnect(room, "bob", 1_500).toCompletableFuture().join(); print("reconnected bob", service.snapshot(room));
        Settlement settlement = service.settle(room, "settlement-1", "alice-win").toCompletableFuture().join();
        print("settled " + settlement, service.snapshot(room));
        service.close(room).toCompletableFuture().join();
        print("closed", service.snapshot(room));
        System.out.println("room-game=ok|mode=local|events=created,join,ready,start,disconnect,reconnect,settle,close|productionReady=false");
        return "room-game=ok|mode=local|events=created,join,ready,start,disconnect,reconnect,settle,close|productionReady=false";
    }

    private static void print(String event, RoomSnapshot snapshot) {
        System.out.printf("room-event=%s|room=%s|state=%s|seq=%d|members=%d%n",
                event, snapshot.roomId().value(), snapshot.state(), snapshot.sequence(), snapshot.members().size());
    }
}
