package group.zn.zero.runtime.config;

import java.util.List;
import java.util.Comparator;
import java.util.Objects;

/** 已解析配置的不可变、安全诊断快照。@author zn */
public final class RuntimeConfigDiagnostics {

    /** 按 owner 与逻辑名称排序的安全条目。 */
    private final List<RuntimeConfigDiagnosticEntry> entries;

    /**
     * 复制并排序安全条目；无外部副作用，构造后可跨线程读取。
     * @param entries 已脱敏条目；可为空列表，不可含 null。
     * @throws NullPointerException 输入或元素为空时抛出。
     */
    public RuntimeConfigDiagnostics(final List<RuntimeConfigDiagnosticEntry> entries) {
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries")).stream()
                .sorted(Comparator.comparing(RuntimeConfigDiagnosticEntry::owner)
                        .thenComparing(RuntimeConfigDiagnosticEntry::logicalKey)).toList();
    }

    /**
     * 读取快照，无副作用。
     * @return 按 owner 与逻辑 key 排序的不可变条目；可能为空且线程安全。
     */
    public List<RuntimeConfigDiagnosticEntry> entries() {
        return entries;
    }

    /** 按快照内容比较，保证装配计划确定性；无副作用且线程安全。@return 内容相同时为 true。 */
    @Override
    public boolean equals(final Object other) {
        return this == other || other instanceof RuntimeConfigDiagnostics that && entries.equals(that.entries);
    }

    /** 返回内容哈希；无副作用且线程安全。@return 快照内容的哈希值。 */
    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    /** 返回只含安全条目的文本；无副作用且线程安全。@return 安全摘要。 */
    @Override
    public String toString() {
        return "RuntimeConfigDiagnostics{entries=" + entries + '}';
    }
}
