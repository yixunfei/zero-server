# Contributing to zeroServer

感谢你愿意参与 zeroServer。无论是缺陷报告、文档修正、性能证据、Adapter、示例还是完整功能，都欢迎通过 GitHub 协作。

## 开始之前

1. 阅读 [README](README.md)、[总体架构](docs/architecture.zh-CN.md) 和 [模块图](docs/module-map.md)。
2. 搜索现有 [Issues](https://github.com/yixunfei/zero-server/issues)，避免重复工作。
3. Bug 和小型文档修正可以直接提交 Pull Request；新增能力、公共 API、协议或运行时语义变更应先开 Issue 或 Design Proposal。
4. 不要在公开 Issue、日志、测试夹具或提交中放入真实密码、Token、连接串、玩家数据或安全漏洞利用细节。

## 开发环境

- JDK 21。
- Maven 3.9 或更高版本。
- Git。

```bash
git clone https://github.com/yixunfei/zero-server.git
cd zero-server
java scripts/ZeroLocalDoctor.java
mvn -B -ntp test
```

## 设计边界

- 包名前缀为 `group.zn.zero`。
- `zero-core` 不得依赖 Kafka、MongoDB、Redis、PostgreSQL、Nacos 等具体中间件。
- 具体基础设施通过 SPI/Adapter 接入。
- 业务代码不得自行创建线程池；执行器由运行时统一装配和管理。
- 玩家、实体和场景状态遵循 Actor/执行域绑定；跨 Actor 修改通过消息完成。
- 数据访问通过 `Repository` / `DataService` 抽象。
- 异常不得被生吞；对外错误和错误日志应绑定 `ErrorCode`。
- 行为、配置、依赖方向或公共入口发生变化时同步更新文档。

线程模型、协议格式、RPC 语义、存储格式、缓存策略、公共 API/SPI、权限和审计、热更机制、模块依赖方向属于高影响设计。请在实现前通过 Design Proposal 说明目标、非目标、兼容性、性能、安全、回滚和验证方案。

## 代码约定

- 以单一职责、依赖倒置、高内聚低耦合为目标，避免无边界的大文件和大方法。
- 公共类型、成员变量、接口方法和公共方法需要清楚的文档注释；线程安全、数据变更、集合语义和异常边界必须明确。
- 性能热路径关注对象分配、锁、队列积压、阻塞 IO、背压、超时和上下文切换。
- 不为了兼容未发布接口而保留无必要的旧实现；`0.x` 破坏性变更必须写入 Changelog 和迁移说明。

更完整的约定见 [代码规范](docs/code-style.zh-CN.md)。

## 验证

提交前至少运行与改动匹配的验证：

```bash
mvn -B -ntp test
mvn -B -ntp -Pquality verify
```

还可以按需要运行：

```bash
mvn -B -ntp -Pintegration-tests verify
mvn -B -ntp -Pbenchmarks -pl :zero-benchmarks -am -DskipTests package
java scripts/VerifyLocalScaffolds.java --outputDir target/scaffold-verify
```

真实 Kafka、MongoDB、Redis、PostgreSQL 或 Nacos 测试仅由 `-Pexternal-tests` 显式启用。提交性能结论时必须同时提供环境、工作负载、预热、迭代、单位、原始结果和局限，不能只提交一个孤立数字。

## Pull Request

- 每个 PR 聚焦一个清晰目标。
- 描述用户可见行为、受影响模块、兼容性、性能与安全影响。
- 列出实际执行的验证和未验证项。
- 新增或改变行为时更新 README、模块文档或对应设计文档。
- 不提交 `target/`、IDE 配置、日志、本地配置、凭据或内部工作记录。

## License

提交贡献即表示你有权提供该内容，并同意其按本项目 [MIT License](LICENSE) 分发。
