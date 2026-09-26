# 数据安全、缓存与数据库兼容专项审查报告

日期：2026-09-26
仓库：`L:/zero-server`
分支：`codex/quality-baseline-20260926`
基线提交：`9772f3e`

## 审查范围

审查 `zero-data`、`zero-cache`、Redis/MongoDB/PostgreSQL adapter、持久化管理和本地 journal 的数据安全、缓存一致性、数据库兼容、失败恢复与数据库操作性能。先用静态调用链和确定性单元回归确认缺陷，再使用本机隔离 Docker 依赖验证真实 driver 路径。

## 已确认并修复

| 领域 | 发现 | 修复 |
| --- | --- | --- |
| Redis 数据安全 | envelope namespace/collection 直接进入 key 组合；花括号可改变 Cluster hash tag 边界 | 构造阶段拒绝 `{`、`}`，保持合法 key 格式不变 |
| 本地 journal 安全 | 路径片段可包含控制字符、Windows ADS 冒号和非法文件名字符 | 构造阶段拒绝危险字符，避免越界/特殊文件语义 |
| Adapter 注册并发 | `registerRepository` 可能静默替换运行中的实例 | 使用 `putIfAbsent`，重复注册显式失败 |
| Repository 分页 | `offset + limit` 使用 `int`，极大参数会溢出并导致非法 `subList` | 使用 `long` 计算并裁剪到结果大小；增加两套仓库回归 |

## 缓存与版本一致性结论

分层缓存已有读取代际、版本化失效、迟到回填保护和 per-key singleflight；本轮定向回归未发现新的可复现旧值复活、负缓存穿透或取消泄漏缺陷。缓存仍是事实来源之外的加速层，强一致对象必须继续依赖 Repository 版本/CAS、事务或补偿策略。

Redis cache namespace 与数据 envelope 分离，条件写/失效使用 Lua 原子脚本；Redis 故障路径显式计数并保留 loader 回源能力。未把 `allowStaleOnBackendFailure` 当作已实现 stale-read 语义，也未把聚合命中计数解释为 L1/L2 分层命中率。

## 落库场景矩阵

| 场景 | 当前行为 | 结论 |
| --- | --- | --- |
| 新建/更新 | Repository 递增版本，driver 通过条件写/CAS 发布 | 单实例功能通过；需外部故障注入验证重试策略 |
| 版本冲突 | stale expectedVersion 返回 `VERSION_CONFLICT` | Mongo replace、PostgreSQL update、Redis Lua 均有单实例回归 |
| 批量保存/查询 | Repository 同步串行逐对象调用；Redis findAll 逐 ID GET | 语义正确，吞吐受网络 RTT 和 monitor/连接开销限制 |
| 分页 | driver/envelope repository 先 `findAll`，内存切片 | 适合小数据集；大集合会产生 O(n) 读取和内存峰值 |
| Redis 故障 | 条件写失败显式报告，本地 journal 保留失败现场 | 已验证失败边界；未证明磁盘满、进程强杀后的完整恢复 |
| PostgreSQL 连接 | 默认 `PGSimpleDataSource` 每次操作获取连接；可注入调用方 DataSource | 兼容性清晰，但生产必须自行提供连接池和限流 |
| Mongo 查询 | 使用 cursor 读取全部文档，未声明排序 | 不依赖顺序时安全；需要稳定分页顺序的业务应显式定义排序契约 |

## 性能审核

已确认的静态成本：

- `ZeroDataEnvelopeCrudRepository` 所有入口为 `synchronized`，数据库 IO 会占用 repository monitor，串行化同一仓库的并发读写。
- `findByIds`、`saveAll` 和删除批次逐对象执行，没有跨对象批量 driver API。
- `findPage` 先完整读取并解码，再在内存切片；数据规模增长时延迟和峰值内存随总量增长。
- Redis `findAll` 为 bucket `SMEMBERS` 后逐 ID `GET`，存在 N+1 网络访问；`count` 逐 bucket `SCARD`。
- PostgreSQL 默认路径不创建连接池；Mongo/Redis driver 的连接池由各自客户端或调用方配置管理。
- 本地 journal 每次 append 同步 `FileChannel.force(true)`，可靠性较高但吞吐受磁盘 flush 影响。

这些是容量和部署配置风险，不在本轮猜测性改变公共 API、CAS、批量语义或存储格式。正式 benchmark 应按 `docs/operations/evidence/repository-save-performance-evidence.zh-CN.md` 和 `cache-get-or-load-performance-evidence.zh-CN.md` 的 workload、payload、并发、p95/p99、失败保留指标采样。

## 真实 driver 验证

- Redis 7.4-alpine：`127.0.0.1:6387`，`RedisDataAdapterExternalIT` 通过。
- MongoDB 7.0：`127.0.0.1:27018`，`MongoDataAdapterExternalIT` 通过。
- PostgreSQL 16：`127.0.0.1:15432`，`PostgresqlDataAdapterExternalIT` 通过。
- 数据库隔离目录和凭据文件位于 `M:\docker_space\containers\data-cache-db-audit-20260926`，凭据未写入仓库或报告。

## 剩余风险

本报告不证明 Redis Cluster/replica set、MongoDB replica set、PostgreSQL HA、网络分区、跨版本兼容、容量、长稳、进程强杀恢复或 SLA。外部 Kafka/Nacos 不属于本轮数据落库验证范围。
