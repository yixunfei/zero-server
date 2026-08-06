package group.zn.zero.gm;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存 GM 审计 hook。
 *
 * @author zn
 */
public final class InMemoryGmAuditHook implements GmAuditHook {

    /**
     * 审计事件列表。
     */
    private final CopyOnWriteArrayList<GmAuditEvent> events = new CopyOnWriteArrayList<>();

    /**
     * 记录 GM 审计事件。
     *
     * <p>本方法会修改内存事件列表；内部使用 copy-on-write 列表保证并发读写安全，适合测试、
     * 本地原型和低频后台命令，不适合高吞吐生产审计落地。</p>
     *
     * @param event GM 审计事件；不可为空。
     * @throws NullPointerException 当审计事件为空时抛出。
     */
    @Override
    public void record(final GmAuditEvent event) {
        events.add(Objects.requireNonNull(event, "event"));
    }

    /**
     * 返回已记录的 GM 审计事件快照。
     *
     * @return 审计事件快照；不可为空；有序；可能为空；返回集合不可变且线程安全。
     */
    public List<GmAuditEvent> events() {
        return List.copyOf(events);
    }
}
