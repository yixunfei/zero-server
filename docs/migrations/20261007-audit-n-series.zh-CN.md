# 2026-10-07 审计 N1–N9 事实复核迁移

适用于当前 `0.1.2-SNAPSHOT` 工作树；未升级版本或发布。用户已确认本轮修复范围。原报告部分指控与当前代码/契约不符，逐项裁定见[复核报告](../audits/2026-10-06-full-code-audit.zh-CN.md)。

## 必须处理的调用方变化

1. **presence API（破坏性源码/二进制变化）**：Java `readPresenceBits()` 改为 `readPresenceBits(int knownFieldCount)`；C#、TypeScript、GDScript 同步增加必填参数。使用本次 codegen 重新生成全部协议产物；手写 codec 传入 schema 中 nullable 字段数量，不能将网络声明长度直接当作上限。旧 Java 已编译 codec 必须重新编译，无参二进制入口不保留。没有更改协议 ID、presence 打包格式或对象线格式。
2. **GM 请求**：声明 `targetParameterName` 的命令，其 DSL 实参必须与 REST/RPC 请求 `target` 完全一致。不同值返回 `OPERATION_AUTHORIZATION_DENIED`（REST 403），不调用 handler。未声明固定目标参数的命令仍使用请求目标。
3. **GM 审批**：命令定义或入口策略任一要求审批，就必须装配能够验证该实际目标的 `GmApprovalVerifier` 并提交有效 token。默认 failClosed 不会接受。dry-run 及幂等重放也重新授权。此轮不实现 token 一次性消费，其权威状态仍由应用 verifier 管理。
4. **内存路由**：已知路由即使过期，也不接受较小 epoch 或同 epoch 的不同 owner；同 epoch 同 owner 的合法续租允许。调用方必须传播正确 fencing token。该原型仍不连接权威存储，不能替代 PostgreSQL 路由的权威 lease 验证。
5. **告警规则导出**：修正控制字符、非法指标名/标签键后重新导出。组名、告警名、表达式与摘要使用 YAML 引号，文本快照断言需更新；有效规则解码后的语义不变。标签值中的引号和反斜杠分别经过 PromQL 与 YAML 两层转义。独立复核补充：配置中的 U+FFFE/U+FFFF 或孤立 UTF-16 代理项现在也会使导出抛出 `IllegalArgumentException`；应在配置源清理这些不能直接写入 YAML 的字符后重试，不能依赖编码器静默替换。合法 emoji 等补充字符仍保留，无公共 API 或线格式变更。

## 保留的设计

- Redis 非负十进制版本按长度优先比较，跨位数本来正确；无需转换为定长版本或迁移 Redis 数据。
- KCP claim 发放新票据、新 generation 和新密钥，方向序号从 1 开始。客户端使用新的 connect info；不恢复旧票据接收窗口。
- Frame close 拒绝尚未执行的队列命令，不是等待或取消已经进入 simulation 的同步屏障；共享无状态路由器生命周期属于 scheduler。
- ServiceDiscovery 默认查询保留不健康实例供发现/诊断；调用方选路须明确查询过滤条件。RPC 已有健康/enabled/weight 过滤，不改发现默认值。
- presence 保留现有未知尾字段跳过契约；仅展开已知位，其余完整位图边界检查后跳过。

## 验证

逐项测试、修复前失败记录、完整命令和未覆盖环境收录在[任务验收记录](../../tasks/archive/20261007-audit-n-series-verification/VERIFY.md)。不通过修改历史 API baseline 隐藏无参 presence 入口移除；兼容门禁的预期失败单独列明。

独立复核的追加修复、修复前失败及本轮实际验证见[当前状态验收](../../tasks/archive/20261007-audit-report-current-state/VERIFY.md)。
