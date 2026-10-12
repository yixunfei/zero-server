# 2026-10-12 新增 examples 审核修复

本次调整覆盖当前新增的业务示例、模块接入卡片和模块级 examples 文档，以及 AOI/帧同步示例；框架公共 API 与运行时实现未修改。以下示例 API 和数据格式直接修正，不提供开发期兼容层。

## 每日奖励示例

- 删除 `DailyRewardClaim` 和独立领取仓库。`DailyRewardInventory` 升为 schemaVersion 2，增加 `claimDay`、`claimKey`，与库存一起进行一次版本条件写。
- `DailyRewardService` 构造参数改为库存 Repository、查询 CacheService、EventBus、Clock。参考当前 BusinessSliceApplication 重建组合根。
- 奖励固定为服务端 coin/100；同一玩家当天换请求键仍返回 DUPLICATE，回显原领取键。跨玩家相同键互不影响；业务时钟回拨到已领取日期前会拒绝请求。
- 发奖资格读权威仓库，缓存仅用于查询。提交后的缓存失效或通知失败仍向上传播；重试不再加库存，也不自动补发通知。
- 本地示例重启即可重建内存数据。若曾自行将旧格式持久化，不能直接丢弃旧领取记录：需按玩家和业务日期核对历史库存/领取记录，将最近日期和请求键与库存一起迁移；无法确认是否已发奖时先拒绝领取。不要通过删除数据掩盖未知提交结果。

## 玩家职责示例

- UID 改为单实例递增分配；仅限当前内存生命周期，不能用于已持久化账号或跨进程发号。
- 所有 PlayerLifecycleService 业务方法必须在创建它的普通命令行线程调用。框架 Actor 会话操作仍走消息；同步 join 不可直接放入 Actor/Netty/Kafka 回调。
- 重连在修改旧会话前校验参数和认证；创建角色要求完整有效会话，保存后读回提交版本再缓存。CenterRouter 的注册表与粘性 UID 路由共享同一来源。
- MultiServerError 携带示例 ErrorCode。角色门面返回 RpcResult.failure，调用方应检查 success/errorCode 或调用 orThrow，不能依赖门面直接抛旧的裸 RuntimeException。
- RPC DTO 增加字段校验。接口只是本地职责草图，缺少 codec、远端 handler 与真实认证；RoleLauncherMain 仅输出接入提示，不再报告虚假的 ready。

## 运行命令与文档

- 四组件探针改为 `mvn -f examples/multi-server-player/pom.xml -Pexternal-probe compile exec:exec`，在独立 JVM 中运行，避免 SDK 全局线程与 Maven classloader 关闭冲突。
- PowerShell 入口为 `examples/multi-server-player/scripts/verify-external.ps1`。默认创建唯一项目，退出删除本次容器/网络并保留具名卷；`-Keep` 留下容器，`-UseExisting` 只读取五个显式连接环境变量，不启动 Docker。
- 示例配置只读取 ZERO_KAFKA_BOOTSTRAP、ZERO_NACOS_ADDRESS、ZERO_MONGO_URI、ZERO_MONGO_DATABASE、ZERO_REDIS_URI；不再接受 JVM 数据库属性覆盖这些端点。
- AOI/帧同步输出新增真实恢复、重复消息、输入和通知结果。若有脚本匹配旧输出，请按当前场景 README 更新。
- 62 张模块卡片和对应模块 README 链接真实调用方法、模块测试或组合根源码；外部测试可能跳过，构建成功不等于真实服务通过。NPC 的历史模拟另行标明，正式入口指向 zero-npc 测试。

## 验证证据

2026-10-12 使用本机 JDK 21、Maven 3.9.8：

| 场景 | 测试数 | 结果 |
| --- | ---: | --- |
| business-slice | 7 | 通过，含跨玩家、同日换键、跨日/回拨、陈旧缓存、提交后失败和溢出 |
| multi-server-player | 9 | 通过，含 UID 冲突、会话校验、稳定路由、提交版本、回源及线程约束 |
| aoi-state-sync | 1 | 通过，核对实体状态、基线版本与恢复 |
| frame-sync | 1 | 通过，核对真实输入和提交通知 |

四个场景均执行 `-Pquality -Dmaven.multiModuleProjectDirectory=L:/zero-server verify`，Checkstyle、PMD、SpotBugs 通过并生成 JaCoCo 报告；所有 main 入口运行通过。

真实 Docker 环境使用 Kafka 3.9.1、Nacos 3.2.2、MongoDB 7.0、Redis 7.2，四项探针通过并输出 `MULTI_SERVER_PLAYER_EXTERNAL_OK`。探针使用随机记录并清理；已停止/移除本次容器和网络，保留具名卷。环境与容器日志位于 `M:/docker_space/containers/zero-player-probe-6ae19cc6ca68/`，引擎物理磁盘在 `M:/docker_space` 下。

另以命令替身验证脚本成功/失败清理、原始错误保留、环境变量恢复、独立 JVM 命令以及 UseExisting 不调用 Docker。模块文档源码路径、方法名和相对链接已核对。

完整任务与构建日志保留在本地审核归档；上表与命令记录可用于重新验证。

未覆盖真实跨进程玩家业务、持久化账号/UID、可靠事件恢复及容量长稳；四组件探针只证明各组件的独立连通读写，不能据此声称完整分布式业务通过。
