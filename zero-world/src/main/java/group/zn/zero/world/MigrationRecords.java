package group.zn.zero.world;

import group.zn.zero.core.error.ZeroException;
import java.util.HashMap;
import java.util.Map;

/** 本地迁移收据目录；由服务状态锁保护，终态不淘汰以免旧 ID 重做。 @author zn */
final class MigrationRecords {
    /** 包括终态的总容量。 */
    private final int capacity;
    /** 有界记录目录。 */
    private final Map<String, Migration> records = new HashMap<>();
    /** 已确认归档的单调序号水位，旧序号永久拒绝。 */
    private long retiredThrough;
    /** 过渡旧入口分配的本地序号。 */
    private long legacySequence;
    /** 旧入口幂等键到序号的稳定映射。 */
    private final Map<String, Long> legacySequences = new HashMap<>();

    MigrationRecords(final int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("migrationCapacity must be positive");
        this.capacity = capacity;
    }

    /** 冻结实体之前检查；调用方须先处理旧收据。 */
    void requireCapacity() {
        if (records.size() >= capacity) throw ZeroException.of(WorldErrorCode.MIGRATION_CAPACITY_EXCEEDED);
    }

    /** 已准入 ID 可继续推进，不再次消耗容量。 */
    void put(final String id, final Migration migration) {
        if (!records.containsKey(id)) requireCapacity();
        records.put(id, migration);
    }

    Migration get(final String id) { return records.get(id); }

    /** 判断 ID 是否已经落在显式归档水位之前；不修改目录。 */
    boolean isRetired(final long sequence) { return sequence <= retiredThrough; }

    /** 调用方已归档终态结果后推进水位；在途记录阻止整个批次，失败不删除任何记录。 */
    int acknowledgeThrough(final long sequence) {
        if (sequence <= 0 || sequence < retiredThrough) {
            throw new IllegalArgumentException("migration watermark must not move backwards");
        }
        for (Migration migration : records.values()) {
            if (migration.sequence() <= sequence && migration.state() != MigrationState.COMPLETED
                    && migration.state() != MigrationState.FAILED) {
                throw new IllegalStateException("cannot retire an active migration");
            }
        }
        int before = records.size();
        records.entrySet().removeIf(entry -> entry.getValue().sequence() <= sequence);
        retiredThrough = sequence;
        return before - records.size();
    }
    /** 过渡旧入口按首次见到顺序分配稳定序号。 */
    synchronized long legacySequence(final String id) { return legacySequences.computeIfAbsent(id, ignored -> ++legacySequence); }
    /** 过渡旧归档入口按收据查找序号。 */
    int acknowledgeThrough(final String id) {
        Migration migration = records.get(id);
        if (migration == null) throw new IllegalArgumentException("unknown migration id");
        return acknowledgeThrough(migration.sequence());
    }
    int size() { return records.size(); }
}
