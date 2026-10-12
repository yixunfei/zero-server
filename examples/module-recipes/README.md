# 模块接入与常见场景

七个独立 Java 21 应用示例，直接调用框架正式 API。每个目录都有自己的 POM、`main`、可检查的结果、中文注释及场景回归；默认只使用本机内存组件，不需要 Docker。

## 选择场景

| 目标 | 可运行示例 | 核心接入与常见失败 |
| --- | --- | --- |
| 创建最小应用 | [runtime-basics](runtime-basics/README.md) | 配置、装配诊断、受管任务、关闭；缺少必填键 |
| 将事件交给玩家 Actor | [event-actor](event-actor/README.md) | GameActorGateway、lane 状态与 trace；非法命令不改状态 |
| 查找可用服务实例 | [discovery](discovery/README.md) | 分组、集群、健康、标签选择；下线和注销 |
| 接入正式 NPC 模块 | [npc](npc/README.md) | LocalNpcZone、生命周期、操作预算；重复创建和行为失败 |
| 防止旧 owner 覆盖状态 | [ownership](ownership/README.md) | 租约、CAS、幂等、主动交接；旧租约/旧版本拒绝 |
| 提供运维指令入口 | [gm](gm/README.md) | REST 适配、可信身份、审批、dry-run、幂等和审计 |
| 排查生产接入配置 | [production](production/README.md) | 缺失键、安全错误、脱敏和基础生命周期 |

目录依赖彼此独立；聚合 POM 只统一构建与测试。GM/production 的驱动依赖不会传入基础示例。`zero-gm-rest` 当前没有列入 BOM，因此该子工程显式声明框架版本。

## 运行与验证

在仓库根目录使用 JDK 21、Maven 3.9+；先用 `java -version` 确认实际使用的 JDK。首次运行或框架源码变化后安装当前框架：

```powershell
mvn -B -ntp -DskipTests install
mvn -B -ntp -f examples/module-recipes/pom.xml test
```

按需进入一个场景，例：

```powershell
mvn -B -ntp -f examples/module-recipes/gm/pom.xml test exec:java
```

聚合 POM 没有 `main`，不要对它直接执行 `exec:java`。一次执行全部入口：

```powershell
$recipes = 'runtime-basics','event-actor','discovery','npc','ownership','gm','production'
foreach ($recipe in $recipes) {
    mvn -B -ntp -f "examples/module-recipes/$recipe/pom.xml" test exec:java
    if ($LASTEXITCODE -ne 0) { throw "示例执行失败: $recipe" }
}
```

使用仓库质量配置（仍从仓库根目录执行）：

```powershell
$repoRoot = (Get-Location).Path.Replace('\', '/')
mvn -B -ntp -f examples/module-recipes/pom.xml -Pquality "-Dmaven.multiModuleProjectDirectory=$repoRoot" verify
```

`main` 打印结果并退出；测试断言业务状态、ErrorCode、拒绝路径和资源关闭，不能只凭输出包含成功字段判断接入正确。异步示例只在命令行/测试线程限时等待，不在 Actor 或事件回调内部阻塞。

## 其他模块与场景

| 模块能力 | 已有可运行入口 |
| --- | --- |
| 协议、代码生成、逻辑与 TCP | [rpg-tcp-generated](../rpg-tcp-generated/README.zh-CN.md) |
| 玩家、场景、Starter | [rpg-minimal](../rpg-minimal/README.zh-CN.md)、[玩家职责](../multi-server-player/README.md) |
| Repository、缓存、事件、日志、指标 | [每日奖励业务切片](../business-slice/README.zh-CN.md) |
| MongoDB、Redis、PostgreSQL 与 runtime Provider | [Repository 组合](../repository-composition/README.md)，外部模式需实际中间件 |
| RPC、RPC common、runtime RPC | [本地 center/logic](../modular-composition/center-logic/README.md) |
| Kafka RPC、服务发现适配器 | [Kafka 双进程](../modular-composition/center-logic-kafka/README.md)、[四组件探针](../multi-server-player/README.md) |
| KCP、安全会话 | [真实本机 TCP/UDP](../kcp-tcp-login/README.zh-CN.md) |
| 房间、AOI、状态同步、帧同步 | [room-game](../room-game/README.md)、[aoi-state-sync](../aoi-state-sync/README.zh-CN.md)、[frame-sync](../frame-sync/README.md) |
| 排行、赛季、世界/分片 | [ranking-season](../ranking-season/README.md)、[world-shard](../world-shard/README.md) |
| 热更、日志、监控、定时任务 | [配置热更](../config-hot-reload-local/README.zh-CN.md)、[可观测性](../observability-local/README.zh-CN.md)、[受管调度](../managed-scheduler-local/README.md) |
| Parent、BOM、脚手架及其他装配/支撑模块 | [62 个模块接入卡片](../module-access/README.md)、[脚手架 catalog](../scaffold-catalog/README.md) |

内存 ownership、GM fixture 和生产配置诊断有明确使用边界，不能替代分布式、认证服务或外部 Adapter 的验收。详细限制与替换位置见各子目录。

返回 [全部示例](../README.md)。变更与验证证据见 [迁移说明](../../docs/migrations/20261012-module-recipes.zh-CN.md)。
