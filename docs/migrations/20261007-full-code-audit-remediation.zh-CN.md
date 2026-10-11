# 2026-10-07 全量代码审计收尾

本次针对 [2026-10-06 审计报告](../audits/2026-10-06-full-code-audit.zh-CN.md) 全部剩余条目进行源码复核、修复及证据关闭。用户已确认全量修改，UID 无需兼容或保留旧账号数据。这是 `0.x` 开发阶段的 API 与行为调整，不提供旧错误位、旧 UID 算法或旧签名兼容分支。

## 身份、协议与安全

- 删除 `LocalPlayerService.withStableHashUid` 两个工厂及截断 hash 实现。调用方必须显式注入 `PlayerUidResolver`，对接具备唯一约束的权威账号到 UID 映射；相同账号稳定返回同一 UID，不同账号不得共用 UID。已有构造器继续承担接线入口。框架不会自动搬迁、保留或清理旧账号数据库。
- `ProtocolFeature.ERROR` 使用 `0x0010`；`COMPRESSED` 仍为 `0x0001`。新 `ProtocolErrorFrames.response` 保留请求 protocolId/version，生成 `error|protocol=<id>|reason=<稳定错误码>` UTF-8 负载，不复制请求的压缩、加密或扩展标志。同步更新生产 replay 拒绝和 local TCP 模板，客户端须使用 ERROR 判错。
- replay 检查等待采用 `ProductionNetworkConfig.authenticationTimeout()`；错误帧发送同步抛错或异步失败均记为 CHANNEL_ERROR 并关闭连接。连接内检查仍按序执行。
- `NettyHttpServer` 新增带 `metadataVerificationTimeout` 的构造器，原便利入口默认 3 秒。校验超时拒绝请求，晚到的成功不会再次响应；verifier 发起调用本身仍必须及时返回 CompletionStage，不得同步阻塞。
- `SecurityMetadataAssertion.digest` 改用 HmacSHA256。canonical 输入包括版本、长度前缀、排序后的权限和毫秒时间戳（与 HTTP/Kafka wire 时间精度一致）。旧签名失效，应重新签发断言；HTTP/Kafka wire 字段未变。该帮助方法不能代替生产密钥管理、轮换和身份校验。
- KCP 下发票据时，钳制后的剩余有效期必须至少覆盖 `bindTimeout`，不足则返回 AUTHENTICATION_EXPIRED，且不占用会话容量；不会延长原安全上下文。
- 日志额外转义 Unicode 行/段分隔符和双向控制符。Prometheus HELP/label 共用合法转义，CR/LF 均编码为字面量 `\n`，不是不受支持的 `\r`。指标模型本身仍拒绝控制字符。

## 数据、缓存与所有权

- Mongo `saveIfVersionBatch` 按输入顺序执行逐项 CAS，返回与输入一一对应的不可变 boolean 列表。版本冲突返回 false 并继续后续项，同 ID 连续版本写按输入顺序执行。为真实结果接受额外数据库往返；不是批次事务。
- Repository `saveAll` 仍返回 `CompletionStage<Void>`，存在冲突时异常完成；成功前缀及后缀可能已提交。真实驱动/网络故障同样可能部分提交或结果未知。调用方应重读并核对版本，不可把异常解释为整批未执行或盲重试。
- 分层缓存写回队列满时，同步和异步 L2 失败均返回 failed CompletionStage，不回填 L1；新 key 容量耗尽仍快速失败。同 key 单飞注册与许可准入改为同一短临界区，loader 不在锁内执行。
- 内存 ownership epoch 溢出通过精确加法失败，不回绕。PostgreSQL 租约最小接受粒度为 1 微秒，低于该粒度拒绝；存储时间戳按微秒对齐。最小粒度不承诺一次数据库往返后租约仍可用，实际 TTL 必须覆盖执行与网络预算。
- 保留非事务分页弱一致契约。acquire/fence 各自在行锁后取数据库时间，epoch 和行锁共同保证权威性；独立操作不共享过时的时间采样。

## 游戏域与运行时

- Room 使用 `RoomDispatchRegistry` 安装共享、无实例状态的处理器，消息绑定具体服务；同调度器多实例不会串用房间表或事件消费者。CREATED 为 sequence 0，其后单调递增。先前私有 `CommandPayload` 重构及本次替换均为内部实现，不应被反射或业务代码依赖。
- World 首次进入按排序后的 shard 列表和 `floorMod(entityId.hashCode(), size)` 选择分片。同拓扑下稳定且不受注册顺序影响；拓扑变化可能改变新进入者归属，这不是一致性哈希，也不会自动迁移已进入实体。
- 删除 World 的 `MigrationState.SOURCE_RELEASED` 和 `WorldEvent.Type.MIGRATION_RELEASED`；真实路径为 TARGET_COMMITTED → COMPLETED。更新应用 switch 分支；ownership 模块自己的迁移模型不受影响。
- `FrameMatchConfig` 独立增加 `maxParticipants`，便利构造器默认 4096；`maxBufferedInputs` 只限制排队输入。REPEAT_LAST 玩家历史按参与者预算保留，业务在离开对局时调用 `FrameMatchRuntime.leave(uid)` 清理历史、待处理输入并释放名额。不得靠盲目淘汰 last-input 改变仍在场玩家的缺帧行为。
- Actor lifecycle 与资源账本共用一次关闭责任，关闭成功后不会再次物理关闭；失败允许再次尝试。
- `GameRuntime.start()` 是控制线程调用的同步入口，健康探针在配置预算内等待；不能在 IO/Actor 线程调用。停机持久化允许一次忽略退避的最终尝试，批次失败即退出并保留脏数据，不循环轰炸故障后端。
- NPC spawn/despawn 的业务失败统一使用 `NpcErrorCode`（INVALID_STATE、ALREADY_EXISTS、NOT_FOUND）；despawn 先验证快照再移除实体。

## GM、Kafka 与本地入口

- GM 幂等容量拒绝使用 CAPACITY_REJECTED / IDEMPOTENCY_CAPACITY_EXCEEDED，REST 429，表示本次没有占位或执行；CONFLICT、IN_PROGRESS、FAILED 分别返回独立错误码及 REST 409。调用方按状态决定后续处理。
- 保留 approvalToken 参与幂等指纹，以及 FAILED 在 TTL 内禁止自动重执行。换审批令牌同键冲突；历史失败可能发生在副作用之后，需核实提交结果。TTL 过后重新获得 claim 也不能替代业务幂等。
- Kafka consumer 默认 `max.poll.records=16`、`fetch.max.bytes=32 MiB`、`max.partition.fetch.bytes=20 MiB`，可经 consumerProperties 覆盖。fetch 是软预算，首个超大 record batch 可能超过它；poll 条数也不是堆内存硬上限。codec 单 payload 上限、producer/broker/topic 限制及部署内存需一起规划。
- `ZeroProcess --stateDir` 优先于 `ZERO_STATE_DIR`；smoke 显式传入独立目录。正常/强制/已退出/失败启动清除 ready、ready.tmp、stop 和控制元数据，保留 server.log、server.error.log 等诊断产物。读写与清理检查 run 目录归属及真实路径；身份校验失败不终止无关进程。
- `ZERO_DEBUG=1` 开启 Java/PowerShell 栈诊断；默认保持单行错误契约。local demo/TCP 的业务注册统一在 LocalGameFlow，Server 复用 flow 分发。

## 升级、验证与回退

1. 停止旧受管本地进程，清楚区分运行目录与日志；不要在仍运行的进程上删除控制元数据。
2. 同步框架与应用依赖，接入权威 UID resolver，更新 ERROR 判定与 World enum 引用，重新签发 metadata assertions，明确 frame 参与者预算和离场调用。
3. 在新目录生成 local/local+net 工程并运行测试；已有手写工程遵循 ownership 升级流程，不直接覆盖业务文件。
4. 按 Mongo 部分提交、GM 各幂等状态及 Kafka fetch 软预算审查调用方错误处理。
5. 回退须连同应用、客户端和签发方一起回退到经验证的同一版本；不混用新旧签名或错误位。本次没有账号数据迁移可回滚。

验证命令、实际统计和未覆盖项记录在 [任务验收](../../tasks/archive/20261007-full-code-audit-remediation/VERIFY.md)。审计的最新处置矩阵位于原报告末尾。真实外部测试覆盖本机 MongoDB/PostgreSQL；不把单测或这些集成结果推广为 Kafka/Nacos/Redis、跨机故障、生产容量或原生 Linux/macOS 已验证。
