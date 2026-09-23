# 20260915 Adapter 与混合组合验收切片

## 变更

将验收矩阵中的 `matrix.adapters` 和 `matrix.mixed-composition` 从 skipped 改为真实本地验证：

- `VerifyAdapterCompositions.java` 运行 discovery consumer、Redis consumer 和生产 adapter 配置/装配契约 focused tests。
- `VerifyMixedComposition.java` 运行生成组合验证器，检查 mixed 组合的生成、构建、诊断、依赖闭包、runtime classpath 和无隐式启动边界。

## 验证范围

Adapter 切片覆盖：

- 本地 discovery 不引入 Nacos、Redis 或网络依赖；
- Redis 选择时资源所有权、配置缺失 fail-fast，以及未选 Kafka/Mongo/PostgreSQL/Nacos/Netty/Starter driver 不进入 consumer classpath；
- production config resolver、runtime assembly contract 和 Nacos provider contract 的本地 focused tests。

Mixed 切片使用 `data,redis,cache,custom-actor,discovery` 生成工程，验证其生成 marker、diagnose 结果、framework artifact closure、provider/capability manifest 和外部 profile 不默认启动。

## 证据

`ZeroAcceptanceEvidence` 新增两个记录：

- `matrix.adapters`，日志：`target/acceptance-evidence/logs/adapters.log`；脚本内部日志：`target/adapter-composition-verify/`。
- `matrix.mixed-composition`，日志：`target/acceptance-evidence/logs/mixed-composition.log`；脚本内部日志：`target/mixed-composition-verify/verify.log`。

命令失败、超时或稳定 marker 缺失都返回非零；不使用历史 marker 代替本次执行。

## 回滚与未覆盖风险

删除两个 verifier、evidence records 和本迁移说明即可回滚，不改变运行时公共 API、协议或 adapter SPI。该切片不启动真实 broker/数据库，不证明外部故障恢复、跨平台 runner、容量/长稳、TLS/认证、持久化恢复或发布回滚，因此 `productionReady=false`、`goalAchieved=false` 继续有效。`matrix.no-sdk-external-service` 仍需独立 clean-room 验证。
