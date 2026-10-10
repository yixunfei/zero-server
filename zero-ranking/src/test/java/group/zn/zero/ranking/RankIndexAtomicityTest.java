package group.zn.zero.ranking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 替换预检失败不得删除原条目或破坏排名；不依赖不可恢复的 JVM 内存耗尽。 @author zn */
class RankIndexAtomicityTest {
    /** 空新值和不存在的旧键都在可见修改前失败，之后索引仍可正常替换。 */
    @Test void invalidReplacementKeepsIndexUsable() {
        RankIndex index = new RankIndex();
        RankingEntry old = new RankingEntry("old", 10, 1, 0, 1);
        RankingEntry other = new RankingEntry("other", 5, 2, 0, 1);
        RankingEntry replacement = new RankingEntry("old", 1, 1, 0, 2);
        index.replace(null, old);
        index.replace(null, other);
        List<RankingEntry> before = index.first(10);
        assertThrows(NullPointerException.class, () -> index.replace(old, null));
        assertEquals(before, index.first(10));
        assertThrows(IllegalStateException.class,
                () -> index.replace(new RankingEntry("missing", 7, 0, 0, 1), replacement));
        assertEquals(before, index.first(10));
        assertEquals(1, index.rank(old));
        assertEquals(2, index.rank(other));
        index.replace(old, replacement);
        assertEquals(List.of(other, replacement), index.first(10));
    }
}
