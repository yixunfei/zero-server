# zeroServer

[![CI](https://github.com/yixunfei/zero-server/actions/workflows/ci.yml/badge.svg)](https://github.com/yixunfei/zero-server/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

zeroServer 是面向游戏服务端的 Java 21 模块化框架。通过事件、Actor 和协议代码生成开发业务，通过独立 Adapter 接入网络、RPC、数据库和运维能力。

当前版本为 `0.1.0-SNAPSHOT` 开发预览，`productionReady=false`。本地原型、组件装配和多种真实 Adapter 已实现；完整服务治理、安全运营、容量和长稳仍需完善。具体状态统一维护在[能力矩阵](docs/capability-matrix.zh-CN.md)。

## 从需要的场景开始

| 需求 | 推荐入口 |
| --- | --- |
| 快速写单体游戏原型 | `local` 业务模板：生成协议、BO、登录和场景流程，默认无中间件 |
| 空白内核或只要 Actor/事件 | `runtime` 模板，按 `--components` 选择；最小仅 core/runtime/bootstrap 三个框架制品 |
| 中心与逻辑服务器 | 先用本地 RPC 示例复用接口；拆进程时独立选择 Kafka，不必引入 Nacos 或数据库 |
| 分布式基础设施 | 按需选择 Kafka、Nacos、MongoDB、Redis、PostgreSQL，再接入应用自己的治理策略 |

`zero-server-starter` 与 Production Starter 是全量便利组合。精简应用使用[独立集成模块](docs/guides/modular-composition-guide.zh-CN.md)，无需依赖全部框架。

## 快速运行

准备 JDK 21、Maven 3.9+ 和 Git，在仓库根目录执行：

```bash
git clone https://github.com/yixunfei/zero-server.git
cd zero-server
java scripts/ZeroLocalDoctor.java
java scripts/RunLocalPrototype.java --fromKeywords "rpg scene sync" --projectName my-game --packageName group.example.mygame
```

最后一条命令安装当前 SNAPSHOT，生成 `target/generated/my-game`，执行测试和本地业务流程。结束后资源关闭，入口退出。打开生成工程的 `BUSINESS_GUIDE.md`，从 `.si` 协议和 `XXXEventBOImp` 开始改业务。

需要最小依赖时，在安装工件后生成：

```bash
java scripts/NewLocalGame.java --template runtime --components event,actor --projectName my-runtime --outputDir target/my-runtime
mvn -q -f target/my-runtime/pom.xml clean test exec:java
```

环境切换、真实 TCP、中心—逻辑和外部 Adapter 的完整步骤见[快速上手](docs/quickstart.zh-CN.md)。默认示例执行一次验证流程；`local + net` 已有 Server 模板，但当前生成工程存在装配编译限制，详见快速上手。

## 文档

从[文档总览](docs/README.md)进入，按任务选择阅读：

| 内容 | 入口 |
| --- | --- |
| 上手与依赖选择 | [快速上手](docs/quickstart.zh-CN.md)、[模板与组件参数](docs/scaffold-templates.zh-CN.md) |
| 写业务与替换实现 | [按需装配](docs/guides/modular-composition-guide.zh-CN.md)、[Repository](docs/guides/repository-composition-guide.zh-CN.md)、[协议生成](zero-codegen/docs/user-guide.zh-CN.md) |
| 理解设计 | [架构](docs/architecture.zh-CN.md)、[模块图](docs/module-map.md)、[线程模型](docs/threading-model.zh-CN.md) |
| 验证与运行 | [本地验收](docs/operations/local-stage0-acceptance.zh-CN.md)、[部署基线](docs/operations/deployment-baseline.zh-CN.md)、[性能方法](docs/operations/performance.zh-CN.md) |
| 当前差距与升级 | [能力矩阵](docs/capability-matrix.zh-CN.md)、[优化路线图](docs/optimization-roadmap.zh-CN.md)、[迁移记录](docs/migrations/README.md) |

核心只依赖中立契约；状态修改遵循 Actor 所有权，远程 IO 使用受管执行器；真实基础设施必须显式选择和配置。框架提供安全、恢复和审计的最小实现与扩展点，生产应用仍需落实真实认证、TLS、持久化、故障恢复和容量验证。

## 贡献与许可

开发与验证流程见[贡献指南](CONTRIBUTING.md)。安全问题按[安全策略](SECURITY.md)报告，社区协作遵循[行为准则](CODE_OF_CONDUCT.md)。变更记录见 [CHANGELOG](CHANGELOG.md)。

采用 [MIT License](LICENSE)，可用于开源或商业项目。
