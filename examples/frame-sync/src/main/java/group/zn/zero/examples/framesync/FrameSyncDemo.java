package group.zn.zero.examples.framesync;

import group.zn.zero.framesync.FrameCommitted;
import group.zn.zero.framesync.FrameInput;
import group.zn.zero.framesync.FrameInputBatch;
import group.zn.zero.framesync.FrameMatchConfig;
import group.zn.zero.framesync.FrameMatchRuntime;
import group.zn.zero.framesync.InputTimingPolicy;
import group.zn.zero.framesync.MissingInputPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * 真实帧同步运行时：输入入队 → tick 消费 → 模拟 → 提交事件与广播。
 * 默认本地调度器同步执行；join 仅用于普通命令行线程，不可照搬到 Actor/Netty 线程。
 * @author zn
 */
public final class FrameSyncDemo {
    private FrameSyncDemo() { }

    /** @param args 未使用参数。运行独立本地对局并输出摘要；失败向上抛出。 */
    public static void main(final String[] args) {
        System.out.println(runDemo());
    }

    /** @return 不可空摘要；调用之间状态独立、线程安全；关闭对局后返回。 */
    public static String runDemo() {
        DemoResult result = runFlow();
        return "frame-sync=ok|mode=local|frameNo=" + result.frameNo()
                + "|inputs=" + result.batches().getFirst().inputs().size()
                + "|events=" + result.events().size() + "|broadcasts=" + result.broadcasts().size();
    }

    /**
     * 将同一输入提交两次，再推进一帧，确认只消费一次；所有回调都在本地对局 lane 执行。
     * @return 不可空结果，各列表不可变、有序；不创建远端连接。
     * @throws IllegalStateException 帧数、输入消费或通知不符合预期。
     */
    public static DemoResult runFlow() {
        List<FrameInputBatch> batches = new ArrayList<>();
        List<FrameCommitted> events = new ArrayList<>();
        List<FrameCommitted> broadcasts = new ArrayList<>();
        FrameMatchConfig config = new FrameMatchConfig(
                InputTimingPolicy.REJECT, MissingInputPolicy.EMPTY, 64, 16, 4, 4);
        try (var match = new FrameMatchRuntime("match-1", config,
                (frame, batch) -> batches.add(batch), events::add, broadcasts::add)) {
            FrameInput input = new FrameInput("player-1", 1, 1, 0, new byte[] {1}, "trace-input");
            // submit 的完成仅表示入队；权威模拟要等 tick。
            match.submit(input).toCompletableFuture().join();
            match.submit(input).toCompletableFuture().join();
            match.tick("trace-tick").toCompletableFuture().join();
            if (match.frameNo() != 1 || batches.size() != 1 || batches.getFirst().inputs().size() != 1
                    || events.size() != 1 || broadcasts.size() != 1) {
                throw new IllegalStateException("frame input or notification was not committed exactly once");
            }
            return new DemoResult(match.frameNo(), batches, events, broadcasts);
        }
    }

    /**
     * 不可变、有序的本地对局结果；列表可能为空，元素为框架不可变快照。
     * @param frameNo 最后提交的权威帧号。
     * @param batches 交给模拟器的输入批次。
     * @param events 已交给事件回调的提交信息。
     * @param broadcasts 已交给广播回调的提交信息；不代表已通过网络送达。
     */
    public record DemoResult(long frameNo, List<FrameInputBatch> batches,
            List<FrameCommitted> events, List<FrameCommitted> broadcasts) {
        /** 复制结果，避免暴露回调的可变收集器；空引用抛 NullPointerException。 */
        public DemoResult {
            batches = List.copyOf(batches);
            events = List.copyOf(events);
            broadcasts = List.copyOf(broadcasts);
        }
    }
}
