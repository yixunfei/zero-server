# 0.x 场景装配调整

适用：`0.1.0-SNAPSHOT` 开发工作树，2026-09-14。没有协议字节格式、持久化格式或数据迁移。

| 调整 | 应用迁移方式 |
| --- | --- |
| 生成 runtime 外部 smoke 改为仅诊断，`runDemo(false)` 不再 build/client-close | 需要验证客户端创建时显式调用 `RuntimeAssembly.create(config)`；需要真实启动使用 `--start` |
| Redis URI 不再写死在生成应用默认值中 | 用外部 properties、JVM 属性或 `ZERO_REDIS_URI` 提供 URI；配置样例仍含本机地址 |
| 外部配置不完整输出 `runtime-diagnosis=incomplete` | 脚本区分 incomplete 与 ok，读取 `missingConfigKeys`；不要把命令退出 0 当作可启动或连通证明 |
| 多数据来源不再把 `main` 隐式绑定到 Redis | 在 `DataRuntime.repositories(...)` 显式设置业务角色；默认按来源名暴露多个角色。单来源仍使用 `main` |
| Kafka/Nacos 与对应本地组件一起选择时使用真实实现 | 删除对已被替换本地 provider 的清单断言；不需要手工排除 POM 依赖 |
| 外部生成装配支持配置指定 standalone/external-test/production | `zero.mode` 从外部配置覆盖，默认仍为 external-test |

新增 `ProductionAssembly.builder(String, ZeroConfig)`、`ProductionAssembly.plan()` 和 `MODE_STANDALONE`。
原有 overload 保留；`plan()` 不创建资源，配置不完整时抛出现有脱敏异常，`diagnose()` 仍汇总缺失键。

运行时修复后的行为：组件间装配超时回滚已登记资源；健康检查超出总启动预算不能进入 RUNNING；诊断后的多值 Builder 可继续追加贡献者，旧快照保持不可变。慢探针必须遵守传入 timeout；框架不强制中断任意同步阻塞代码。

重新生成可能覆盖生成器管理的源文件。已有手写业务项目应先审阅生成差异或在新输出目录生成，再迁移装配文件。

验证证据及命令见 [场景审查报告](../scenario-audit.zh-CN.md)；本地验证不替代真实中间件、多进程、故障恢复或容量验证。
