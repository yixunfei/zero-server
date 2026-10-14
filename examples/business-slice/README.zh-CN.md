# 业务开发纵向切片：每日道具领取

本示例展示类型化异步协议、已认证 TCP、玩家 lane、Repository 条件写、查询缓存、通知恢复、日志和指标。每名玩家每个服务端业务日只能获得一次固定奖励 `coin/100`；同日换客户端请求键也不会再次发放。

```text
已认证 SecurityContext → RequestExecution → 生成 Async BO/帧端点
  → 校验可信玩家身份/权限 → player lane → 校验服务端奖励配置
  → Repository 读取权威库存与领取日期
  → 一次版本条件写：库存 + 日期 + 首次请求键 + 待通知状态
  → 失效查询缓存 → 发布稳定 ID 事件 → CAS 确认 → 类型化响应
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

同一玩家必须串行等待整条异步完成链。返回 CompletionStage 不保证底层非阻塞，也不自动恢复 Actor 归属。BO 通过 ActorRequestExecutor 返回实际 CompletionStage，不阻塞 IO/Actor；仅 CLI 与测试编排等待。请求范围保留身份、trace、期限，取消不撤销已发出的物理写入。

Repository 必须提供原子插入与版本条件写。并发服务实例发生版本冲突时，错误向上传播；调用方可重新查询权威结果，不得盲目重试未知写结果。库存溢出在保存前失败。

库存和领取标记一次提交，缓存失效或事件失败不会造成“已发奖但没有领取记录”。这些提交后失败仍向上传播；再次请求返回 DUPLICATE，不会增发。DailyRewardNotificationReplay 自动分页扫描并在玩家 lane 重放持久化待通知，稳定事件 ID 用于消费者去重。发送成功但确认失败可能重复投递。每玩家一个待通知槽，旧通知未确认前拒绝次日发奖。真实应用仍应明确缓存故障后的读一致性与失效修复策略。

DailyRewardTcpServer 强制注入 SecurityChain，经框架握手认证后才建立请求范围；测试使用随机 token 的验证器。同一 DailyRewardBO 在 local 和 loopback TCP 返回生成 claimResult DTO，并拒绝伪造 UID。应用需自行提供正式账号认证供应商。

握手就绪帧发送或传输监听失败会取消该连接的交付并关闭连接；首个错误通过 `failure()` 健康查询和 `close()` 传播，调用方须处理关闭错误。

真实 PostgreSQL 恢复验收显式执行：

```powershell
# 在本机环境中设置 ZERO_POSTGRESQL_URL、ZERO_POSTGRES_USER、ZERO_POSTGRES_PASSWORD、ZERO_POSTGRESQL_TABLE
mvn -B -f examples/business-slice/pom.xml '-Dtest=*Test,DailyRewardPersistenceExternalIT' test
```

该测试通过 RepositoryCatalog 的 game→postgresql 绑定，关闭并重建 runtime/连接池后确认库存与领取记录，从聚合重放通知并清理独立玩家记录；这是同 JVM 的 runtime 重建，不是进程崩溃测试。普通 test 不默认连接数据库。

schema/codec 已升为 3，需要重建开发数据或显式离线转换。未交付跨进程玩家所有权、分布式 outbox claim、容量或长稳保证；通知是至少一次，不承诺跨资源 exactly-once。见[迁移说明](../../docs/migrations/20261014-product-goal-remediation.zh-CN.md)。
