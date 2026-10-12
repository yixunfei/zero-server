# 事件与玩家 Actor

覆盖模块：zero-event、zero-actor、zero-game、zero-runtime-event、zero-runtime-actor。依赖见 [pom.xml](pom.xml)，入口见 [EventActorExample.java](src/main/java/group/zn/zero/examples/recipes/events/EventActorExample.java) 的 `runFlow()`，测试见 [EventActorExampleTest.java](src/test/java/group/zn/zero/examples/recipes/events/EventActorExampleTest.java)。

## 运行

使用 JDK 21、Maven 3.9+，在仓库根目录执行。首次或框架源码更新后先运行 `mvn -B -ntp -DskipTests install`。

```powershell
mvn -B -ntp -f examples/module-recipes/event-actor/pom.xml test exec:java
```

## 接入步骤

1. 安装受管执行器、`EventRuntime.module()`、`ActorRuntime.module()`。
2. 注册 `Credit` 消息处理器；从 `ActorMessage.payload()` 读取命令，在玩家 lane 中校验并修改积分。
3. 内部事件将 payload 和 traceId 交给 `GameActorGateway.dispatch()`，返回其完成信号。
4. 发布 +10、非法 -1、+20，观察拒绝后正常请求仍可完成。

## 预期结果

points=30，traces=[trace-1, trace-2]，rejectionCode=ZERO-INVALID-ARGUMENT。

`main` 打印 `Result` 后退出；回归测试断言关键结果，异常不会被仅打印后忽略。

## 接入边界

本例只拥有一个固定玩家的 `Wallet`；只由该玩家 lane 写入，主线程等待全部消息完成后读快照。扩展为多玩家时必须为每个 lane 分离状态并通过消息查询，不要让不同 lane 共享可变钱包。lane 保证顺序执行，不保证永久绑定同一个线程 ID。

handler 不调用 `join/get`，只在 main 边界 `get(5, SECONDS)`。异步失败仅解开 `ExecutionException/CompletionException`，保留框架 ErrorCode。两类订阅句柄通过 `try-with-resources` 取消注册，runtime 关闭调度器和执行器；总线不是事务，不会自动回滚跨组件变更，也不提供本例业务的持久幂等。

## 扩展位置

将 `CreditEvent` 替换成生成协议或业务事件，在进入 gateway 前确定玩家 ID 与对应 lane。

完整注释包含参数、结果、状态修改及线程边界；复制时保留组合根的资源关闭逻辑。返回的结果 record 和列表不可变。

返回 [七类示例索引](../README.md) 或 [全部示例](../../README.md)。
