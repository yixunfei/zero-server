# 脚手架 ownership 安全升级（2026-09-14）

## 变更

脚手架现在保留生成文件的 `path/owner/template/sha256` 基线，并提供一个不依赖额外 JSON 运行时的安全升级服务：

- `--plan`：渲染到系统临时目录，执行只读三路比较；不改变工程文件。
- `--diff`：在 plan 基础上输出稳定排序的文件状态和 base/target 摘要；不输出文件内容或敏感配置。
- `--apply`：仅提交无冲突计划。提交前把 before/after 快照写入 `.zero/scaffold/transactions/<id>/`，随后使用受控临时文件原子替换；未知文件不参与事务。
- `--rollback`：按 `.zero/scaffold/LATEST` 找到最近事务，恢复其 before 快照；重复执行是幂等的。
- `--abort`：放弃未提交事务并恢复快照；已提交事务必须使用显式 `--rollback`。
- `--migrate`：显式采用已有 manifest 的文件列表和当前磁盘 hash，只改写 manifest 元数据，不覆盖业务或生成文件。
- `apply`、`rollback`、`abort` 使用工程级 `.zero/scaffold/upgrade.lock` 独占锁；锁文件记录协议版本、进程、线程和获取时间，竞争时返回 `SCAFFOLD-UPGRADE-LOCKED`，不会静默覆盖活动事务。
- 事务状态、`LATEST` 指针和 state 文件使用临时文件加原子替换；不支持 `ATOMIC_MOVE` 的文件系统降级到受控 replace，并保留事务恢复材料。

三路状态使用：

```text
current == base                  -> generator-changed（可更新）
current == target                -> unchanged-target（无需更新）
current != base && current != target -> conflict（停止并人工处理）
```

用户修改过的 generated 文件不会因 `--force` 被覆盖；缺失、旧 schema、重复或无法解析的 ownership 记录默认进入 `SCAFFOLD-MIGRATION-REQUIRED`。CLI 预期失败使用稳定错误码并返回非零退出码。

## CLI 进程契约

脚手架 CLI 现在明确区分进程级结果：`0` 表示成功，`1` 表示普通生成失败，`2` 表示参数解析失败，`3` 表示 plan/diff 或升级被阻塞。失败诊断只写入 stderr，并使用 `SCAFFOLD-GENERATION-FAILED|message`、`SCAFFOLD-INVALID-ARGUMENT|message`、`SCAFFOLD-PLAN-BLOCKED|message` 等稳定前缀；stdout 仅用于成功摘要和 plan 输出。`ZeroAcceptanceEvidence` 会启动独立子进程覆盖四种结果，保存每个 case 的原始 stdout/stderr、预期/实际退出码和匹配结果。


```text
PREPARED -> COMMITTING -> COMMITTED
                      \-> RECOVERY_REQUIRED -> ROLLED_BACK
```

故障注入测试验证替换失败后事务目录仍保留、状态为 `RECOVERY_REQUIRED`，回滚可恢复旧 bytes。事务只触碰 manifest 声明的受控路径，不删除未知用户文件。

## 兼容与边界

这是 0.x 的新增安全操作和 manifest 元数据行为。首次空目录生成路径保持兼容；旧 manifest 不会隐式升级，必须显式执行 `--migrate`，并且不能从缺失文件或不可信记录猜测基线。`--force` 仍可被旧调用方解析，但不再是冲突绕过开关。

当前实现不承诺：进程在任意文件系统指令边界被强制终止时的跨平台完全原子性、复杂重命名和人工三方合并、并发升级锁、所有模板的 ownership schema 自动升级，以及真实 TCP/Kafka/生产运行时能力。`productionReady=false` 保持不变。

## 验证

`ScaffoldUpgradeServiceTest`、`ScaffoldTransactionTest`、`ProjectScaffoldCliTest` 与既有生成器/manifest contract tests 共 15 个测试通过。测试覆盖用户修改拒绝、plan dry-run、CLI 参数互斥、staging 目录、注入替换失败、恢复状态、rollback、显式 migration 和幂等；跨平台极端中断和复杂重命名仍为 `not-proven`。
