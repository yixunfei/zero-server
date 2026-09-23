package group.zn.zero.ranking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 带跨度的排名跳表。由 Board 所属服务锁独占，非线程安全。
 * 更新和排名期望 O(log N)，Top K 顺序遍历 O(K)。不持有业务回调。
 * @author zn
 */
final class RankIndex {
    /** 排序契约，不使用减法以免 long 溢出。 */
    private static final Comparator<RankingEntry> ORDER = Comparator.comparingLong(RankingEntry::score)
            .reversed().thenComparingLong(RankingEntry::tieBreakValue).thenComparing(RankingEntry::uid);
    /** int 容量下足够的最大层数。 */
    private static final int MAX_LEVEL = 32;
    /** 无业务值的哨兵。 */
    private final Node head = new Node(null, MAX_LEVEL);
    /** 单所有者的查找工作区，避免每次更新分配数组。 */
    private final Node[] predecessors = new Node[MAX_LEVEL];
    /** 各层前驱的累计排名。 */
    private final int[] ranks = new int[MAX_LEVEL];
    /** 当前使用层数。 */
    private int levels = 1;
    /** 条目总数。 */
    private int size;

    /** 原子性由外层锁保证；替换旧键并插入新值，参数不可为空。 */
    void replace(final RankingEntry previous, final RankingEntry entry) {
        if (previous != null) remove(previous);
        Node current = head;
        for (int level = levels - 1; level >= 0; level--) {
            ranks[level] = level == levels - 1 ? 0 : ranks[level + 1];
            while (current.next[level] != null && ORDER.compare(current.next[level].entry, entry) < 0) {
                ranks[level] += current.span[level];
                current = current.next[level];
            }
            predecessors[level] = current;
        }
        int height = Math.min(MAX_LEVEL, 1 + Integer.numberOfTrailingZeros(ThreadLocalRandom.current().nextInt()));
        for (int level = levels; level < height; level++) {
            ranks[level] = 0;
            predecessors[level] = head;
            head.span[level] = size;
        }
        levels = Math.max(levels, height);
        Node node = new Node(entry, height);
        for (int level = 0; level < height; level++) {
            Node prior = predecessors[level];
            node.next[level] = prior.next[level];
            node.span[level] = prior.span[level] - (ranks[0] - ranks[level]);
            prior.next[level] = node;
            prior.span[level] = ranks[0] - ranks[level] + 1;
        }
        for (int level = height; level < levels; level++) predecessors[level].span[level]++;
        size++;
        clearWorkspace();
    }

    private void remove(final RankingEntry entry) {
        Node current = head;
        for (int level = levels - 1; level >= 0; level--) {
            while (current.next[level] != null && ORDER.compare(current.next[level].entry, entry) < 0) {
                current = current.next[level];
            }
            predecessors[level] = current;
        }
        Node removed = current.next[0];
        if (removed == null || ORDER.compare(removed.entry, entry) != 0) {
            throw new IllegalStateException("ranking index and UID table disagree");
        }
        for (int level = 0; level < levels; level++) {
            Node prior = predecessors[level];
            if (prior.next[level] == removed) {
                prior.span[level] += removed.span[level] - 1;
                prior.next[level] = removed.next[level];
            } else {
                prior.span[level]--;
            }
        }
        while (levels > 1 && head.next[levels - 1] == null) levels--;
        size--;
    }

    /** 返回从 1 开始的排名；缺失返回 0，只读且非线程安全。 */
    long rank(final RankingEntry entry) {
        int rank = 0;
        Node current = head;
        for (int level = levels - 1; level >= 0; level--) {
            while (current.next[level] != null && ORDER.compare(current.next[level].entry, entry) <= 0) {
                rank += current.span[level];
                current = current.next[level];
            }
            if (current != head && ORDER.compare(current.entry, entry) == 0) return rank;
        }
        return 0;
    }

    /** 返回不可变有序快照，可为空，返回后可跨线程共享；读取需外层锁。 */
    List<RankingEntry> first(final int limit) {
        List<RankingEntry> result = new ArrayList<>(Math.min(size, limit));
        Node current = head.next[0];
        while (current != null && result.size() < limit) {
            result.add(current.entry);
            current = current.next[0];
        }
        return List.copyOf(result);
    }

    private void clearWorkspace() {
        // 不让工作区长期保留已删除的节点及其整条 next 链。
        java.util.Arrays.fill(predecessors, null);
    }

    /** 每层保存到 next 的跨度；只由所属索引修改。 @author zn */
    private static final class Node {
        /** 不可变业务条目，哨兵为 null。 */
        private final RankingEntry entry;
        /** 各层后继。 */
        private final Node[] next;
        /** 各层跨过的底层条目数。 */
        private final int[] span;
        private Node(final RankingEntry entry, final int height) {
            this.entry = entry;
            next = new Node[height];
            span = new int[height];
        }
    }
}
