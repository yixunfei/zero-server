package group.zn.zero.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 配置消费契约：冲突、默认值、来源保留和同次读取稳定性。@author zn */
class ConfigLookupTest {
    /** 隔离文件目录。 */
    @TempDir
    private Path directory;

    /** 显式文件覆盖环境；未覆盖的应用默认值可由系统/环境替换。 */
    @Test
    void fileAndDefaultsRetainOriginsAndPriority() throws Exception {
        Path file = directory.resolve("application.properties");
        Files.writeString(file, "zero.file=file-value\n");
        ZeroConfig config = SourcedZeroConfig.withDefaults(ZeroConfigLoader.fromPropertiesFile(file),
                Map.of("zero.file", "default", "zero.system", "default", "zero.env", "default", "zero.absent", "default"));
        ZeroConfig resolved = ConfigLookup.snapshot(config,
                key -> key.equals("zero.file") || key.equals("zero.system") ? "property" : null,
                key -> key.equals("ZERO_FILE") || key.equals("ZERO_ENV") ? "environment" : null);
        assertEquals("file-value", resolved.require("zero.file"));
        assertEquals(ConfigOrigin.FILE, resolved.origin("zero.file"));
        assertEquals("property", resolved.require("zero.system"));
        assertEquals(ConfigOrigin.SYSTEM_PROPERTY, resolved.origin("zero.system"));
        assertEquals("environment", resolved.require("zero.env"));
        assertEquals(ConfigOrigin.ENVIRONMENT, resolved.origin("zero.env"));
        assertEquals(ConfigOrigin.DEFAULT, resolved.origin("zero.absent"));
    }

    /** 空白值必须交给 decoder 拒绝；不得继续退回环境，并且重复读取不漂移。 */
    @Test
    void blankFirstValueAndMissingReadsAreFrozen() {
        AtomicInteger calls = new AtomicInteger();
        var lookup = new ConfigLookup(new MapZeroConfig(Map.of()),
                key -> calls.incrementAndGet() == 1 ? " " : "changed-secret", key -> "fallback-secret");
        var first = lookup.find(List.of(), List.of("zero.key"), List.of("ZERO_KEY")).orElseThrow();
        assertEquals(" ", first.text());
        assertEquals(first, lookup.find(List.of(), List.of("zero.key"), List.of("ZERO_KEY")).orElseThrow());
        assertEquals(1, calls.get());
        assertFalse(first.toString().contains("secret"));
        assertTrue(new ConfigLookup(new MapZeroConfig(Map.of()), key -> null, key -> null)
                .find(List.of("missing"), List.of(), List.of()).isEmpty());
    }
}
