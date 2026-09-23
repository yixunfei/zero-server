package group.zn.zero.runtime.log;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.log.InMemoryLogSink;
import group.zn.zero.log.LogLevel;
import group.zn.zero.log.LogOperation;
import group.zn.zero.log.LogResult;
import group.zn.zero.log.LogSource;
import group.zn.zero.log.LogType;
import group.zn.zero.log.ZeroLogRecord;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LogRuntimeTest {
    @Test
    void pipelineUsesSelectedConfigurationAndSinkWithoutCreatingReplacedDefault() {
        AtomicInteger defaults = new AtomicInteger();
        InMemoryLogSink replacement = new InMemoryLogSink();
        var composition = RuntimeBasics.builder()
                .install(LogRuntime.module(() -> {
                    defaults.incrementAndGet();
                    return new InMemoryLogSink();
                }))
                .replace(RuntimeBasics.CONFIG, new MapZeroConfig(Map.of("zero.mode", "custom")))
                .replace(LogRuntime.TERMINAL_LOG_SINK, replacement);
        composition.diagnose();
        assertEquals(0, defaults.get());
        try (GameRuntime runtime = composition.build()) {
            runtime.start();
            runtime.require(LogRuntime.LOG_APPENDER).append(ZeroLogRecord.create(
                    Instant.now(), LogLevel.INFO, LogType.RUNTIME, new LogSource("test", "local", "log"),
                    new LogOperation("write", LogResult.SUCCESS, null), "trace-test", "created", Map.of()));
            assertEquals(1, replacement.records().size());
            assertEquals("custom", replacement.records().getFirst().fields().get("runtimeMode"));
            assertEquals(0, defaults.get());
        }
    }
}
