# 2026-10-12 模块接入与常见场景应用

## 变更与迁移

新增 [examples/module-recipes](../../examples/module-recipes/README.md) 的七个独立 Maven 应用消费者，把原先仅有测试入口的能力补充为可直接运行的接入示例。同步 16 个模块的卡片和模块内 examples README，并保留原契约测试参考。

本次只增加示例与导航，不修改框架公共 API、配置解析语义、权限机制、存储或协议格式，不需要迁移业务数据。示例保持 `0.1.2-SNAPSHOT`，不代表版本发布。复制新示例时请同时使用对应子工程 POM，并先安装当前框架依赖；`zero-gm-rest` 当前不在 BOM 中，示例显式声明其版本。

## 场景与实际验证

环境：Windows、JDK 21、Maven 3.9.8。七个应用的 `main` 均执行并正常退出；无外部中间件、端口监听或公告外发。

| 场景 | 关键证据 |
| --- | --- |
| runtime-basics | 2 个基础组件；受管 logic 线程完成任务；必填/默认配置与 CLOSED 状态 |
| event-actor | 两次合法加分共 30；非法请求返回 INVALID_ARGUMENT；成功 trace 顺序保持，后续请求继续 |
| discovery | 只选中 logic-a；下线后无可用候选；恢复后选 logic-c；注销后为空 |
| npc | 次数预算处理 1、跳过 2；充分预算处理 3；1 条行为失败事件；移除后剩余 2 |
| ownership | 请求重放不重复提交；epoch 1→2，最终版本 2、积分 150；旧租约新写/重放/释放均被拒绝 |
| gm | 预演、执行、重放 200；冲突 409；目标/审批/权限 403；身份 401；实际执行仅 1 次及匹配审计阶段 |
| production | Redis MISSING_CONFIG；缺失键明确；CONFIG_MISSING 失败；报告无 fixture 凭证；基础 runtime 启停完成 |

总计 **8 项测试，0 失败、0 错误、0 跳过**。聚合 `quality verify` 通过 Checkstyle、PMD、SpotBugs，并生成 JaCoCo 报告。事件示例补充显式订阅关闭后，已单独重复执行其完整质量验证和 main。

架构守卫 22 条规则通过，0 违规、0 警告；47 份相关文档中的 602 个相对链接目标存在。15 个新增 Java 源/测试文件最大 135 行，文件及方法长度符合仓库门禁。

从仓库根目录复查：

```powershell
mvn -B -ntp -DskipTests install
$repoRoot = (Get-Location).Path.Replace('\', '/')
mvn -B -ntp -f examples/module-recipes/pom.xml -Pquality "-Dmaven.multiModuleProjectDirectory=$repoRoot" verify
$recipes = 'runtime-basics','event-actor','discovery','npc','ownership','gm','production'
foreach ($recipe in $recipes) {
    mvn -B -ntp -f "examples/module-recipes/$recipe/pom.xml" exec:java
    if ($LASTEXITCODE -ne 0) { throw "示例执行失败: $recipe" }
}
```

## 验证边界

- 未运行全仓所有测试或所有 62 个模块的验证命令；框架源码未改。
- GM 的身份、审批、业务、审计与幂等均为本地 fixture/内存实现；401 和解析失败在 endpoint 前，由宿主另记入口安全审计。
- ownership 展示本地原子 fencing 和主动交接；没有验证持久化、宕机接管或多进程一致性。
- NPC 使用真实 LocalNpcZone，但次数预算测试没有验证生产帧延迟或自动周期调度。
- production 只验证配置诊断、脱敏和未安装外部 Adapter 的运行时；真实 Kafka/Nacos/Redis/MongoDB/PostgreSQL 接入仍需对应外部验证。

框架模块依赖方向不变；新增消费者依赖及常见修改位置见[模块图](../module-map.md)。
