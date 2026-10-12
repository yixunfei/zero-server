# 业务开发纵向切片：每日道具领取

本示例展示协议生成、服务端限领、Repository 条件写、查询缓存、内部事件、日志和指标。每名玩家每个服务端业务日只能获得一次固定奖励 `coin/100`；同日换客户端请求键也不会再次发放。

```text
生成 DTO/codec/BO/dispatcher
  → 校验玩家 ID 和服务端奖励配置
  → Repository 读取权威库存与领取日期
  → 一次版本条件写：库存 + 日期 + 首次请求键
  → 失效查询缓存 → 发布事件 → 日志与指标
查询库存 → CacheService → 未命中回源并回填
```

## 运行与结果

仓库根目录使用 JDK 21、Maven 3.9+。首次先安装框架依赖：

```powershell
mvn -B -ntp -DskipTests install
mvn -B -ntp -f examples/business-slice/pom.xml clean test exec:java
```

不需要外部服务。成功摘要：

```text
business-slice=ok|statuses=[ACCEPTED, DUPLICATE]|inventory=100|events=1|logs=2|metrics=1|cacheHits=1|deadLetters=0
```

组合根使用固定 UTC 时钟，保证示例跨午夜运行也得到相同结果。实际业务应注入 `Clock.systemUTC()` 或明确业务时区的时钟；时钟回拨到已领取日期之前时拒绝领取。

## 从这里开始接入

- [DailyReward.si](src/main/protocol/DailyReward.si)：修改协议声明，Maven 在 generate-sources 阶段生成代码。
- [DailyRewardInventory](src/main/java/group/zn/zero/examples/business/DailyRewardInventory.java)：不可变玩家聚合，库存、领取日期和请求键共同参与版本条件写。
- [DailyRewardService](src/main/java/group/zn/zero/examples/business/DailyRewardService.java)：校验、限领和提交后通知；发奖资格只读取权威仓库，缓存只用于查询。
- [BusinessSliceApplication](src/main/java/group/zn/zero/examples/business/BusinessSliceApplication.java)：内存依赖、生成 BO、事件处理器、日志与指标的组合根。
- [业务回归测试](src/test/java/group/zn/zero/examples/business/DailyRewardServiceTest.java)：跨玩家同键、同日换键、跨日/回拨、奖励篡改、陈旧缓存、提交后失败与溢出。

`DUPLICATE` 回显该玩家当天第一次领取的请求键和奖励，不表示另一次发放。协议中的 itemId/quantity 只表达客户端预期，必须匹配服务端配置；真实网络入口必须从认证会话取得或校验 uid，不能信任客户端身份。

## 线程、失败与替换边界

同一玩家必须串行等待整条异步完成链。返回 CompletionStage 不保证底层非阻塞，也不自动恢复 Actor 归属。示例的 BO 使用 join，仅适用于普通命令行线程与本地依赖；远程 IO 需受管执行域，状态变更需回到正确 Actor。

Repository 必须提供原子插入与版本条件写。并发服务实例发生版本冲突时，错误向上传播；调用方可重新查询权威结果，不得盲目重试未知写结果。库存溢出在保存前失败。

库存和领取标记一次提交，缓存失效或事件失败不会造成“已发奖但没有领取记录”。这些提交后失败仍向上传播；再次请求返回 DUPLICATE，不会增发，也不自动补发通知。真实应用应明确缓存故障后的读一致性与失效修复策略；可靠事件需要将 outbox 与聚合一起提交，本例未实现。

示例不提供真实账号认证、跨进程玩家所有权、可靠消息、容量或长稳保证。聚合格式调整见[迁移说明](../../docs/migrations/20261012-examples-review.zh-CN.md)。