package __PACKAGE__;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import java.util.Map;

/**
 * Local game scaffold regression test.
 *
 * @author zn
 */
class __TEST_CLASS__ {

    @Test
    void runDemoShouldCompleteGeneratedProtocolFlow() {
        __APP_CLASS__.DemoResult result = __APP_CLASS__.runDemo();
        String summary = result.summaryLine();

        assertAll("local game scaffold result",
                () -> assertEquals("__RUNTIME_PROFILE__", result.mode()),
                () -> assertEquals("__PROJECT_NAME__", result.name()),
                () -> assertEquals(1001L, result.uid()),
                () -> assertEquals(3, result.position().x()),
                () -> assertEquals(5, result.position().y()),
                () -> assertEquals(7, result.logCount()),
                () -> assertEquals(6, result.metricCount()),
                () -> assertEquals(__PACKAGE__.generated.protocol.ProtocolIds.MAX_ID, result.maxProtocolId()),
                () -> assertTrue(summary.startsWith("local-game=ok|mode=__RUNTIME_PROFILE__|name=__PROJECT_NAME__")),
                () -> assertTrue(summary.endsWith("|maxProtocolId=" + __PACKAGE__.generated.protocol.ProtocolIds.MAX_ID)));
    }
    /** Formal player loading must consume the configured catalog instead of a fixture-only store. */
    @Test
    void playerLoadsProfileFromSelectedRepository() {
        var config = group.zn.zero.core.config.ZeroConfigLoader.loadStandard(java.util.Map.ofEntries(__CONFIG_DEFAULTS__));
        try (var runtime = RuntimeAssembly.create(config, new group.zn.zero.log.InMemoryLogSink())) {
            runtime.start();
            long uid = 1_000_000L + java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L);
            var repository = runtime.require(group.zn.zero.runtime.data.DataRuntime.REPOSITORIES).create(
                    new group.zn.zero.data.repository.RepositoryRequest<>("players", group.zn.zero.player.PlayerProfileRepository.DEFINITION));
            repository.save(new group.zn.zero.player.PlayerProfile(uid, 0, "catalog-profile", false)).toCompletableFuture().join();
            try (var fixture = new LocalGameFixture(runtime)) {
                var profile = fixture.playerService().loadPlayer(new group.zn.zero.player.PlayerLoadRequest(uid, "catalog-load"))
                        .toCompletableFuture().join();
                assertEquals("catalog-profile", profile.name());
                assertEquals(1, profile.version());
            } finally { repository.deleteById(uid).toCompletableFuture().join(); }
        }
    }
}
