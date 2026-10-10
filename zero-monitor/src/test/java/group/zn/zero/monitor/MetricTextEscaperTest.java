package group.zn.zero.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import group.zn.zero.core.error.ZeroException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 转义器纵深防御和公有指标构造边界联合验证。 @author zn */
class MetricTextEscaperTest {
    @Test void newlinesCannotIntroduceAnotherSeries() {
        for (boolean label : new boolean[]{true, false}) {
            var text = new StringBuilder();
            MetricTextEscaper.append(text, "safe\nforged 999\r\n\\\"", label);
            assertEquals(label ? "safe\\nforged 999\\n\\n\\\\\\\"" : "safe\\nforged 999\\n\\n\\\\\"", text.toString());
            assertEquals(1, text.toString().lines().count());
        }
        assertThrows(ZeroException.class,
                () -> new MetricDefinition("safe", "help\nforged 999", "count", List.of()));
        assertThrows(ZeroException.class,
                () -> new MetricSample("safe", 1, Map.of("kind", "ok\nforged 999"), Instant.EPOCH));
    }
}
