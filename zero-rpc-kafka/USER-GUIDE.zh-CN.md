# zero-rpc-kafka 用户注意事项与使用说明

`zero-rpc-kafka` 是 `zero-rpc` 的 Kafka 传输 Adapter。它负责把 `RpcTransport` 的 request/response 与 oneway 调用映射为 Kafka 消息，并处理 request topic、reply topic、pending 请求表、超时、错误响应和消费者订阅。

当前适用场景：

- 跨进程服务接口调用。
- 低频或中频跨服查询。
- oneway 通知。
- 本地开发中使用内存 gateway 的单元测试。

不建议场景：

- 高频帧同步。
- 强实时战斗循环。
- 需要严格 exactly-once 的核心交易。
- 在 Actor 或 Netty IO 线程上同步等待远程 RPC。

## 一、模块边界

依赖方向：

```text
zero-rpc-kafka -> zero-rpc -> zero-rpc-common
zero-rpc-kafka -> org.apache.kafka:kafka-clients
```

注意事项：

- `zero-core` 不允许依赖 Kafka；Kafka 只能出现在 Adapter 模块。
- 业务 common 接口不应依赖 `zero-rpc-kafka`。
- 业务调用方仍通过 `RpcClientFactory` 获取 common 接口代理。
- 服务方仍通过 `RpcServiceBinder` 绑定 common 接口实现。
- Kafka 只负责传输，不负责业务幂等、权限、审计、数据一致性。

## 二、Kafka RPC 消息语义

标准字段：

- `correlationId`：请求/响应关联 ID。
- `replyTopic`：调用方接收响应的 topic。
- `serviceName`：路由服务名，通常形如 `player.remote:v1`。
- `methodName`：路由方法名，当前为 methodId 字符串。
- `traceId`：链路追踪 ID。
- `timeoutAt`：请求过期时间。
- `mode`：`REQUEST_RESPONSE` 或 `ONEWAY`。
- `topic`：可选 request topic 覆盖。
- `group`：可选 provider consumer group 覆盖。
- `partitionKey`：Kafka message key，空时回退到 `correlationId`。
- `payload`：业务参数或响应结果字节。

默认语义：

- request/response：调用方注册 pending，发送 request，等待 reply topic 上的响应。
- oneway：调用方只等待 Kafka send 完成，不等待远端业务结果。
- consumer 收到已超过 `timeoutAt` 的 request 时拒绝执行业务 handler。
- Kafka send 失败时 request 会快速失败，不无限排队。
- pending 表有容量限制，容量满时快速失败；pending 超时由内部时间轮统一扫描，不为每个请求创建独立 `ScheduledFuture`。

## 三、Provider 服务方启动示例

服务方进程负责创建 `KafkaRpcAdapter`，绑定 common 接口实现，并保持进程生命周期内 adapter 存活。

```java
package group.zn.zero.example.rpc.kafka;

import group.zn.zero.example.rpc.common.PlayerRemoteRpc;
import group.zn.zero.example.rpc.runtime.PlayerRpcCodecs;
import group.zn.zero.example.rpc.server.PlayerRemoteRpcImpl;
import group.zn.zero.rpc.codec.RpcCodecRegistry;
import group.zn.zero.rpc.kafka.KafkaRpcAdapter;
import group.zn.zero.rpc.kafka.KafkaRpcSettings;
import group.zn.zero.rpc.server.RpcBoundService;
import group.zn.zero.rpc.server.RpcServiceBinder;

/**
 * 玩家 RPC Provider 启动示例。
 *
 * @author zn
 */
public final class PlayerRpcProviderBootstrap {

    /**
     * Kafka RPC adapter。
     */
    private final KafkaRpcAdapter adapter;

    /**
     * 已绑定服务。
     */
    private final RpcBoundService boundService;

    /**
     * 创建 Provider 启动器。
     *
     * @param bootstrapServers Kafka broker 地址；不可为 null。
     */
    public PlayerRpcProviderBootstrap(final String bootstrapServers) {
        KafkaRpcSettings settings = KafkaRpcSettings.defaults(bootstrapServers);
        RpcCodecRegistry codecRegistry = PlayerRpcCodecs.createRegistry();
        this.adapter = new KafkaRpcAdapter(settings);
        this.boundService = new RpcServiceBinder(adapter, codecRegistry)
                .bind(PlayerRemoteRpc.class, new PlayerRemoteRpcImpl());
    }

    /**
     * 关闭 Provider。
     */
    public void close() {
        boundService.close();
        adapter.close();
    }
}
```

注意：

- `@RpcService.topic` 非空时会覆盖默认 request topic。
- `@RpcService.group` 非空时会覆盖 provider 默认 consumer group。
- 关闭顺序建议先解绑服务，再关闭 adapter。
- 当前 adapter 内部会启动 Kafka consumer worker，必须调用 `close()` 释放资源。

## 四、Caller 调用方示例

调用方创建自己的 `KafkaRpcAdapter`，并把 `replyTopic` 设置到 `RpcCallOptions`。

```java
package group.zn.zero.example.rpc.kafka;

import group.zn.zero.example.rpc.common.PlayerProfileDTO;
import group.zn.zero.example.rpc.common.PlayerQueryDTO;
import group.zn.zero.example.rpc.common.PlayerRemoteRpc;
import group.zn.zero.example.rpc.runtime.PlayerRpcCodecs;
import group.zn.zero.rpc.RpcCallOptions;
import group.zn.zero.rpc.RpcCallContext;
import group.zn.zero.rpc.client.RpcClientFactory;
import group.zn.zero.rpc.codec.RpcCodecRegistry;
import group.zn.zero.rpc.common.RpcResult;
import group.zn.zero.rpc.kafka.KafkaRpcAdapter;
import group.zn.zero.rpc.kafka.KafkaRpcSettings;

/**
 * 玩家 RPC Caller 示例。
 *
 * @author zn
 */
public final class PlayerRpcCallerExample {

    private PlayerRpcCallerExample() {
    }

    /**
     * 查询玩家名称。
     *
     * @param bootstrapServers Kafka broker 地址；不可为 null。
     * @param playerId 玩家 ID。
     * @return 玩家名称；不为 null。
     */
    public static String queryPlayerName(final String bootstrapServers, final long playerId) {
        KafkaRpcSettings settings = KafkaRpcSettings.defaults(bootstrapServers);
        RpcCodecRegistry codecRegistry = PlayerRpcCodecs.createRegistry();

        try (KafkaRpcAdapter adapter = new KafkaRpcAdapter(settings)) {
            PlayerRemoteRpc client = new RpcClientFactory(
                    adapter,
                    codecRegistry,
                    RpcCallOptions.defaults()
                            .withReplyTopic(settings.replyTopic())
                            .withTimeoutMillis(3000))
                    .create(PlayerRemoteRpc.class);

            RpcResult<PlayerProfileDTO> result = RpcCallContext.with(
                    RpcCallContext.empty().withTraceId("trace-player-query-" + playerId),
                    () -> client.queryPlayer(new PlayerQueryDTO(playerId)));
            return result.orThrow().name();
        }
    }
}
```

注意：

- 每个 caller 实例应使用自身可区分的 `replyTopic`。
- `KafkaRpcSettings.defaults(...)` 会生成随机 clientId 和 replyTopic，适合 demo；生产建议显式配置。
- 同步调用会等待远端响应，不要在 IO 线程或 Actor 线程中调用。

## 五、显式 Kafka 配置示例

生产环境建议显式配置 clientId、group、topicPrefix、replyTopic、pendingCapacity、pollTimeout 和 closeTimeout。

```java
package group.zn.zero.example.rpc.kafka;

import group.zn.zero.rpc.kafka.KafkaRpcSettings;
import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;

/**
 * Kafka RPC 配置示例。
 *
 * @author zn
 */
public final class KafkaRpcSettingsExample {

    private KafkaRpcSettingsExample() {
    }

    /**
     * 创建 caller 配置。
     *
     * @param nodeId 当前节点 ID；不可为 null。
     * @return Kafka RPC 配置；不为 null。
     */
    public static KafkaRpcSettings callerSettings(final String nodeId) {
        return new KafkaRpcSettings(
                "127.0.0.1:9092",
                "game-" + nodeId + "-rpc-caller",
                "game-" + nodeId + "-rpc-caller-group",
                "zero.rpc",
                "zero.rpc.reply." + nodeId,
                4096,
                Duration.ofMillis(100),
                Duration.ofSeconds(3),
                Map.of(
                        ProducerConfig.ACKS_CONFIG, "all",
                        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true",
                        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "3000"),
                Map.of(
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest",
                        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true"));
    }

    /**
     * 创建 provider 配置。
     *
     * @param serviceGroup 服务消费组；不可为 null。
     * @return Kafka RPC 配置；不为 null。
     */
    public static KafkaRpcSettings providerSettings(final String serviceGroup) {
        return new KafkaRpcSettings(
                "127.0.0.1:9092",
                "game-" + serviceGroup + "-rpc-provider",
                serviceGroup,
                "zero.rpc",
                "zero.rpc.reply.provider-unused",
                4096,
                Duration.ofMillis(100),
                Duration.ofSeconds(3),
                Map.of(ProducerConfig.ACKS_CONFIG, "all"),
                Map.of(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest"));
    }
}
```

注意：

- 当前 gateway 默认设置 `ENABLE_AUTO_COMMIT_CONFIG=true`，如果覆盖为 `false`，当前实现不会主动提交 offset，可能导致重复消费。
- `pendingCapacity` 应按调用方最大并发 request/response 数估算。
- `pollTimeout` 越小，关闭和响应越灵敏，但 poll 开销更高。
- `closeTimeout` 太小可能导致 consumer worker 还没退出。

## 六、oneway Kafka 调用示例

```java
package group.zn.zero.example.rpc.kafka;

import group.zn.zero.example.rpc.common.PlayerKickDTO;
import group.zn.zero.example.rpc.common.PlayerRemoteRpc;
import group.zn.zero.rpc.common.RpcResult;

/**
 * Kafka oneway 调用示例。
 *
 * @author zn
 */
public final class KafkaOnewayExample {

    private KafkaOnewayExample() {
    }

    /**
     * 发送踢下线通知。
     *
     * @param client RPC 客户端代理；不可为 null。
     * @param playerId 玩家 ID。
     * @return true 表示 Kafka send 完成；不代表远端业务成功。
     */
    public static boolean kickPlayer(final PlayerRemoteRpc client, final long playerId) {
        RpcResult<Void> result = client.kickPlayer(new PlayerKickDTO(playerId, "duplicate login"));
        return result.success();
    }
}
```

适用：

- 非关键通知。
- 可丢弃或可重试的后台动作。
- 已有其他通道确认最终状态的业务。

不适用：

- 扣费。
- 发奖。
- 角色转服。
- 任何必须确认远端成功的操作。

## 七、真实 Kafka broker 外部测试示例

当前默认单元测试使用内存 gateway，不等价于真实 Kafka broker。建议为上线前增加 external test。

```java
package group.zn.zero.example.rpc.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.zn.zero.example.rpc.common.PlayerProfileDTO;
import group.zn.zero.example.rpc.common.PlayerQueryDTO;
import group.zn.zero.example.rpc.common.PlayerRemoteRpc;
import group.zn.zero.example.rpc.runtime.PlayerRpcCodecs;
import group.zn.zero.example.rpc.server.PlayerRemoteRpcImpl;
import group.zn.zero.rpc.RpcCallOptions;
import group.zn.zero.rpc.RpcCallContext;
import group.zn.zero.rpc.client.RpcClientFactory;
import group.zn.zero.rpc.codec.RpcCodecRegistry;
import group.zn.zero.rpc.kafka.KafkaRpcAdapter;
import group.zn.zero.rpc.kafka.KafkaRpcSettings;
import group.zn.zero.rpc.server.RpcBoundService;
import group.zn.zero.rpc.server.RpcServiceBinder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 真实 Kafka broker 外部测试示例。
 *
 * @author zn
 */
@EnabledIfSystemProperty(named = "zero.external.tests", matches = "true")
final class PlayerRpcKafkaExternalIT {

    /**
     * 验证真实 broker request/reply。
     */
    @Test
    void shouldCallPlayerRpcThroughRealKafkaBroker() {
        String brokers = System.getProperty("zero.kafka.bootstrap", "127.0.0.1:9092");
        RpcCodecRegistry codecs = PlayerRpcCodecs.createRegistry();
        KafkaRpcSettings providerSettings = KafkaRpcSettings.defaults(brokers);
        KafkaRpcSettings callerSettings = KafkaRpcSettings.defaults(brokers);

        try (KafkaRpcAdapter provider = new KafkaRpcAdapter(providerSettings);
             KafkaRpcAdapter caller = new KafkaRpcAdapter(callerSettings)) {
            RpcBoundService bound = new RpcServiceBinder(provider, codecs)
                    .bind(PlayerRemoteRpc.class, new PlayerRemoteRpcImpl());
            PlayerRemoteRpc client = new RpcClientFactory(
                    caller,
                    codecs,
                    RpcCallOptions.defaults()
                            .withReplyTopic(callerSettings.replyTopic())
                            .withTimeoutMillis(5000))
                    .create(PlayerRemoteRpc.class);

            PlayerProfileDTO profile = RpcCallContext.with(
                    RpcCallContext.empty().withTraceId("trace-external-it"),
                    () -> client.queryPlayer(new PlayerQueryDTO(10086L))).orThrow();

            assertEquals("player-10086", profile.name());
            bound.close();
        }
    }
}
```

运行示例：

```text
mvn -pl zero-rpc-kafka -am -Pexternal-tests verify -Dzero.external.tests=true -Dzero.kafka.bootstrap=127.0.0.1:9092
```

## 八、风险与缓解

### Kafka 不可用

风险：request/response 发送失败或 broker 不可用时调用会失败；pending 不会无限排队。

缓解：

- 调用方必须处理 `TRANSPORT_UNAVAILABLE`。
- 关键业务使用重试队列或业务补偿，不要在 RPC runtime 内无限重试。
- 配置 Kafka producer `delivery.timeout.ms`，避免长时间悬挂。

### 超时边界竞争

风险：调用方超时后，服务方可能已经开始执行；服务方收到请求时若已超过 `timeoutAt` 会拒绝执行，但分布式系统存在边界竞争。

缓解：

- 关键业务必须幂等。
- `@RpcMethod.idempotent=true` 只作为契约声明，不等于 runtime 自动幂等。
- 使用业务 requestId 或操作流水号做去重。

### pending 容量耗尽

风险：调用方并发过高时 pending 表满，会快速失败。

缓解：

- 根据峰值并发调整 `pendingCapacity`。
- 对调用方做限流、熔断和批量合并。
- 高价值请求可使用异步队列削峰。

### pending 时间轮精度风险

风险：当前 pending 超时由内部时间轮按 tick 扫描，超时触发可能比精确时间晚一个或少量 tick；已完成请求的旧时间轮任务会被 pending 表幂等忽略。

缓解：

- RPC 方法超时不要配置到过小，业务默认仍建议秒级或数百毫秒级。
- 关键业务不要依赖毫秒级超时精度做状态判定。
- 关注 pending 数、超时数和调用耗时分布，而不是只看单次超时点。

### replyTopic 共享风险

风险：多个 caller 错误共享 replyTopic 且 consumer group 配置不当，可能造成响应被其他实例消费或互相干扰。

缓解：

- 每个 caller 实例使用唯一 replyTopic。
- replyTopic 包含 serverId、processId 或随机后缀。
- 生产中把 replyTopic 纳入节点注册信息和监控。

### consumer 自动提交风险

风险：当前 gateway 默认自动提交 offset。handler 执行失败后消息也可能已经提交，不能依赖 Kafka 重放完成业务重试。

缓解：

- RPC handler 内部要返回明确错误，不要依赖 consumer 重放。
- 需要可靠重试时使用业务事件队列、死信队列或专门消息消费模型。
- 不建议简单关闭 auto commit，当前实现没有手动提交逻辑。

### oneway 成功语义风险

风险：oneway 成功只代表 send 完成，不代表 provider handler 成功。

缓解：

- 只用于通知类操作。
- 必须确认结果时使用 request/response。
- 可以追加业务回执事件或审计日志链路。

### 内部线程管理风险

风险：当前 `KafkaRpcPendingRequests` 已改为内部时间轮线程，不再为每个请求创建 `ScheduledFuture`；`ApacheKafkaRpcMessageGateway` 仍直接启动虚拟线程 consumer worker，pending 时间轮线程和 consumer worker 尚未纳入框架统一线程管理。

缓解：

- 当前阶段确保所有 adapter 都调用 `close()`。
- 在线上运行前补充线程数、pending 数、poll 异常指标。
- 后续优化应引入统一调度/执行抽象，由框架集中管理时间轮和 consumer worker 生命周期。

### wire 格式兼容风险

风险：`KafkaRpcEnvelopeCodec` 当前 wire version 为 1，修改字段顺序或编码会破坏兼容。

缓解：

- 不在同一 version 内改变 wire 格式。
- 需要升级时新增 version，并保留旧版本解码。
- 回滚期间 provider 和 caller 保持兼容版本。

### payload 大小风险

风险：当前单个字符串字段最大 1 MiB，payload 最大 16 MiB。超限会解码失败。

缓解：

- 大对象不要走 RPC payload，改为对象存储或数据服务引用。
- 批量结果分页。
- 只传必要字段和版本号。

## 九、场景示例附录

### 场景 1：跨服查询

```java
PlayerProfileDTO profile = client.queryPlayer(new PlayerQueryDTO(playerId)).orThrow();
```

风险：同步等待会占用当前线程，Kafka 抖动会放大延迟。

缓解：Actor/IO 链路使用异步方法，低频后台和 GM 可同步。

### 场景 2：跨服通知

```java
boolean sent = client.kickPlayer(new PlayerKickDTO(playerId, "duplicate login")).success();
```

风险：`sent=true` 不代表玩家已经被踢。

缓解：需要最终确认时由目标服发回业务事件。

### 场景 3：自定义 topic 与 group

```java
@RpcService(
        name = "player.remote",
        version = 1,
        topic = "player.rpc.custom",
        group = "player-provider-group")
interface PlayerRemoteRpc {
}
```

风险：topic/group 配错会导致服务不可达或多个服务实例消费关系错误。

缓解：在启动日志和监控中输出 serviceName、version、topic、group。

### 场景 4：高并发调用限流

```java
KafkaRpcSettings settings = new KafkaRpcSettings(
        "127.0.0.1:9092",
        "caller-1",
        "caller-1-group",
        "zero.rpc",
        "zero.rpc.reply.caller-1",
        1024,
        java.time.Duration.ofMillis(100),
        java.time.Duration.ofSeconds(3),
        java.util.Map.of(),
        java.util.Map.of());
```

风险：超过 1024 个未完成请求后，新请求会快速失败。

缓解：调用方用限流器控制并发，或调大 pendingCapacity 并监控内存。

### 场景 5：处理调用失败

```java
RpcResult<PlayerProfileDTO> result = client.queryPlayer(new PlayerQueryDTO(playerId));
if (!result.success()) {
    String code = result.errorCode().code();
    String message = result.errorMsg();
    // 生产代码应写入错误日志、指标和必要的业务补偿队列。
}
```

风险：只检查返回对象不检查 `success()` 会把失败当成功。

缓解：统一使用 `orThrow()` 或统一 RPC 调用包装器。
