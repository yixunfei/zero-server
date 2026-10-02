package group.zn.zero.rpc.kafka;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** 测试范围计时器；每个用例释放框架外部注入资源。 @author zn */
final class KafkaTestTimer implements AfterEachCallback {
    /** 仅供本测试用例使用的计时器。 */
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    /** @return 测试拥有的计时器。 */
    ScheduledExecutorService executor() { return timer; }
    /** 停止全部计时任务并验证线程退出。 */
    @Override public void afterEach(final ExtensionContext context) throws Exception {
        timer.shutdownNow();
        if (!timer.awaitTermination(3, TimeUnit.SECONDS)) throw new IllegalStateException("test timer did not terminate");
    }
}
