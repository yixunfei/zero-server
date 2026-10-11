# 2026-10-09 全量审计第二轮迁移说明

本说明对应 `docs/audits/2026-10-06-full-code-audit.zh-CN.md` 的第二轮收口。项目仍处于 `0.x`，以下行为和 API 调整可能需要调用方修改。

## 生命周期与 Actor

- Actor handler 的异步结果受到受管超时监测。超时后该 lane 被隔离并拒绝新消息；底层任务真实结束前不会释放串行许可。调用方应使 handler 可取消、可幂等，并将不可控远程 IO 移出 Actor lane。
- 运行时启动、探针和关闭共用单调时间预算。预算耗尽时资源被隔离并报告失败，不能把迟到完成视为成功关闭。
- Nacos SDK 在途操作按配置的 request timeout 有界等待；仍在执行的 SDK 调用会阻止关闭底层 client。
- Kafka RPC 生命周期在状态锁外调用外部 factory、register、unregister 和 close；装配方仍须为同一生命周期提供串行关闭顺序。

## 耐久数据、World 与 Ranking

- 迁移收据、排行榜评分收据和赛季归档边界新增显式单调 `long` 序号入口。序号由调用方按提交顺序持久化并重放；字符串 ID/幂等键仅用于身份与精确重放，不再承担顺序比较。旧字符串入口保留为 0.x 过渡适配，并在进程内分配序号，跨进程调用方应迁移到显式序号 API。

- `LocalDurableJournal` 写入 schema v2 并持久化 replay watermark。旧日志可读取；新写入会记录确认后的回放边界。
- World 和 Ranking 不再以静默 TTL/LRU 淘汰可重放收据。业务完成归档或导出后，必须通过对应的确认/保留 API 推进水位；低于水位的重试被拒绝。
- World 删除必须从所属 entity lane 发起，迁移中的世界不能删除。Ranking 赛季只有在终态导出后才允许释放历史状态。

## 配置热重载

- 本轮仅交付本地单进程 CSV 配置热重载，并要求 `ConfigReloadAuthorizer` 明确授权。未配置授权器或授权拒绝时更新失败并保留旧快照。
- CGLIB 修复、ClassLoader 插件化、远程上传、签名审批和集群同步尚未实现，不能据此宣称具备代码热更或集群热更能力。

## 会话与代码生成

- 网络装配层应保存登录返回的精确 `sessionId`，并在连接关闭时交给 `PlayerConnectionCloseCoordinator`。旧连接的迟到关闭不会清除替换登录后的会话；逻辑会话目录仍由传输回调以连接身份关闭。
- 代码生成输出路径拒绝 `COM`/`LPT` 的 ASCII 数字及 Unicode 上标数字变体。生成目标使用这类路径段时需改名。
- `ZeroReader.readPresenceBits(int)` 保持可用，本轮没有删除或改变其兼容契约。

## 复核增量（2026-10-10）

- HTTP 请求完整聚合和业务响应期限由 `ServerOptions.httpRequestTimeout` 控制，默认仍为 30 秒；长轮询、报表或 SSE 需要显式提高该值，且期限仍是绝对预算。
- 迁移收据归档后的历史查询返回缺失，使用已归档 ID 再次发起迁移会返回 `MIGRATION_RETIRED`，不会重新占用容量或重放副作用。
- 磁盘总容量指标改为 GAUGE；它是瞬时值，不能按 COUNTER 使用 `rate()`。
- AOI 观察快照在等值换引用时更新当前实体身份，同时保留上一比较快照，避免可变 state 破坏增量通知。
- KCP 冻结或对端静默期间不使用普通发送进度期限关闭会话；迁移生命周期仍由连接空闲/租约期限控制。
- GM 本地幂等记录不会按 TTL 自动回收 `IN_PROGRESS`：框架无法区分仍在运行的长操作与崩溃遗留。存储达到 4096 条时会 fail-closed 拒绝新 claim；只有业务/运维确认副作用结果并显式写入终态后才释放容量，禁止盲目重放未知结果。
- 监控告警标签统一使用 `metric.label.` 命名空间；超长键采用有界 SHA-256 后缀，避免覆盖固定字段或超过日志键预算。
- 本地 EventRuntime 将处理器派发提交到受管 `backgroundExecutor`，与逻辑 Actor/请求执行域隔离。调用方不得在该执行器内阻塞等待同一事件总线的后续派发；默认单线程/直执行器只适用于本地原型。
- Kafka RPC 响应发送连续失败最多 3 次后记录失败并提交该请求 offset，避免永久重投。该终止边界不提供持久 DLQ；部署方应监控 `SEND_FAILED` 事件与错误日志，达到上限时对应 RPC 响应可能丢失。
- `DataRuntime.module()` 的默认执行器仅支持未绑定快照；包含线程绑定实体的应用需调用 `module(PersistenceBindingExecutor)` 显式提供所属执行域。新建实体保存前需使用 `codec.prepareForSave` 分配并保留 ID。
- 重复 HTTP header 继续按安全策略拒绝。若反向代理会把多个同名 header 合并或透传，需在代理和应用边界配置为一致的拒绝/规范化策略。

## 验证

```powershell
$env:JAVA_HOME='D:\env\jdk21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -Pquality verify
.\mvnw.cmd -Pintegration-tests verify
```

具体日志保存在 `tasks/active/20261009-audit-round2/evidence/`。
