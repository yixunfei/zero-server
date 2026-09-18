# 备份与恢复 Runbook

本文是 zeroServer 部署基线的操作模板，不是对任何业务数据可靠性的保证。各项目必须根据实际数据分类、RPO/RTO、合规要求和外部中间件版本补充并演练；Compose 基线本身不提供跨主机灾备。

## 责任与目标

部署负责人维护镜像 digest、配置版本、依赖清单和恢复记录；数据负责人确认 PostgreSQL/Redis 以及项目外部 MongoDB、Kafka、Nacos 的备份策略。先定义：

- RPO：可接受的数据丢失窗口。
- RTO：从故障确认到服务恢复的目标时间。
- 备份保留期、加密位置、访问审批和删除策略。
- 恢复时允许的降级：只读、停写、延迟队列或完全停服。

未完成恢复演练前，不要声称具备灾备能力或生产就绪。

## 备份前检查

1. 记录应用镜像 digest、Compose 文件版本、`.env` 键名（不记录 secret 值）和 schema 版本。
2. 确认备份目标独立于运行主机，并启用加密、访问审计和保留策略。
3. 检查磁盘空间、数据库健康、复制/持久化状态和最近一次备份结果。
4. 对写入敏感业务安排维护窗口或明确一致性策略；不要在未知写入状态下直接复制卷。
5. 备份凭据通过外部 secret manager 注入，禁止写入命令历史和日志。

## PostgreSQL 逻辑备份示例

以下示例只适用于基线内置 PostgreSQL。外部托管 PostgreSQL 应使用其原生快照/PITR 方案并记录版本与参数。

```bash
set -eu
mkdir -p backup/$(date +%Y%m%dT%H%M%SZ)
export BACKUP_DIR="backup/$(date +%Y%m%dT%H%M%SZ)"
docker compose --env-file deploy/.env -f deploy/docker-compose.yml --profile middleware exec -T postgres \
  pg_dump --format=custom --no-owner --no-acl --dbname="${POSTGRES_DB:-zero}" \
  > "$BACKUP_DIR/postgres.dump"
sha256sum "$BACKUP_DIR/postgres.dump" > "$BACKUP_DIR/SHA256SUMS"
```

实际执行前应确认密码注入方式、备份目录权限和加密上传流程。逻辑备份不等同于时间点恢复；需要 PITR 时必须配置 WAL 归档、监控和恢复验证。

## Redis 备份边界

Redis 在本基线中用于可持久化但不必然等同于主数据的场景。先由业务明确 key 是否可重建：

- 可重建缓存：记录失效策略，不把 Redis 备份当作唯一恢复来源。
- 会话、排行榜或追加日志：定义一致性、过期、重放和恢复顺序；使用 RDB/AOF 或托管服务快照，并验证版本兼容。
- 不要在高写入期间直接复制正在变化的 `/data` 卷来宣称一致备份。

## 应用配置和外部依赖

备份项目提供的非 secret 配置、协议/数据库 schema 版本、镜像 digest 和部署清单。secret 不进入 Git；按组织策略在 secret manager 中独立备份、轮换和恢复。Kafka 需要记录 topic 配置、消费位点策略和保留期；MongoDB、Nacos 等外部组件需分别执行其官方一致性备份流程。

## 恢复演练

在隔离环境执行，不覆盖唯一生产数据：

1. 创建与目标版本匹配的干净主机/卷，安装已审计的 Docker Compose 版本。
2. 恢复 secret 和脱敏配置，固定 app 镜像 digest，先启动依赖服务。
3. 恢复 PostgreSQL 到临时数据库，执行完整性检查、schema 检查和关键业务抽样。
4. 按业务约定恢复 Redis；验证缓存重建、过期策略和重复消费是否安全。
5. 启动 app，确认 healthcheck、日志脱敏、连接池、队列/Actor 水位和关键读写路径。
6. 验证外部依赖连通性、鉴权、协议兼容和幂等性；记录实际 RTO/RPO。
7. 保留命令、版本、时间、校验和、失败项及修复动作，形成可审计演练记录。

## 故障处理与回滚

- 先冻结变更并保留现场：镜像 digest、日志、指标、配置键名和错误时间窗。
- 数据损坏或误写时先停止扩大写入，再选择最近一致备份或 PITR；不要直接删除卷。
- 应用发布失败时回滚到已验证 digest，同时保持数据迁移兼容；应用回滚不自动回滚数据库 schema。
- 若恢复依赖外部中间件，按依赖顺序恢复：数据存储 → 消息/发现 → app → 网关流量。
- 恢复后进行只读核验和业务方签字，再逐步放量；保留原故障环境直到完成取证。

## 定期检查

至少按项目 RPO/RTO 周期执行：备份成功率、备份可读性、校验和、加密与权限、保留期、恢复耗时、数据抽样、secret 轮换、镜像漏洞和 Compose 配置差异。任何“备份成功”都不能替代实际恢复演练。

部署入口见[Docker Compose 部署基线](deployment-baseline.zh-CN.md)。
