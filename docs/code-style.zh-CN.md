# 代码规范

## 1. 包名

Java 包名前缀：

```text
group.zn.zero
```

Maven groupId：

```text
group.zn.zero
```

## 2. 注释

所有注释尽可能使用中文。

必须有注释：

- 类。
- 接口。
- 抽象类。
- 枚举。
- record。
- DTO。
- 测试类。
- 成员变量。
- 接口方法。
- 公共方法。

方法注释必须说明：

- 业务功能。
- 参数含义。
- 返回值。
- 线程是否安全。
- 可能的数据变更。
- 注意事项。
- 可能向上抛出的异常和原因。

返回集合时必须说明：

- 是否可变。
- 是否有序。
- 是否可能为空。
- 是否线程安全。

复杂流程建议使用字符流程图。

默认 author：

```text
zn
```

## 3. 异常

要求：

- 不允许生吞异常。
- 不允许只打印日志后继续执行而不处理。
- 必须接入统一异常处理模块。
- 需要返回给调用方的错误必须绑定 ErrorCode。
- 错误日志必须绑定 ErrorCode。

## 4. ErrorCode

需要统一 ErrorCode 中心。

建议分类：

- 客户端请求业务模块错误。
- 服务器间调用错误。
- 数据访问错误。
- 缓存错误。
- 权限错误。
- 协议错误。
- 热更错误。
- 系统错误。

示例：

```text
ZERO-CLIENT-1001
ZERO-RPC-2001
ZERO-DATA-3001
ZERO-AUTH-4001
ZERO-HOT-5001
```

## 5. 质量门禁

需要使用：

- Checkstyle。
- PMD。
- SpotBugs。
- JaCoCo。

核心性能模块需要补充 JMH 或压测说明。

阶段 2A 起提供独立 Maven 质量门禁入口：

```powershell
mvn -Pquality verify
```

默认 `mvn test` 只执行本地单元测试，不绑定 Checkstyle、PMD、SpotBugs、JaCoCo 的失败门禁，避免未安装外部中间件或正在进行局部开发时阻塞普通验证。质量门禁 profile 用于提交前、阶段验收和 CI 场景。

当前最小规则集：

- Checkstyle：文件末尾换行、禁止星号 import、外部 public 类型与文件名一致。
- PMD：禁止空 catch 块。
- SpotBugs：默认只阻断 High 及以上问题。
- JaCoCo：生成覆盖率报告，阶段 2A 暂不设置覆盖率阈值。

当前不启用 CPD 复制代码失败门禁。`zero-codegen` 的多语言生成器存在结构性重复；后续应通过独立 Design Proposal 评估是否抽取公共模板或调整 CPD 阈值，避免机械去重损害生成器可读性。

## 6. 单元测试

测试需要围绕业务与事件进行。

常见测试：

- 模拟协议参数。
- 模拟玩家状态。
- 模拟场景状态。
- 模拟事件返回。
- 模拟异常与错误码。
- 模拟幂等和重复请求。

测试分层约定：

- 单元测试：命名为 `*Test.java`，由默认 `mvn test` 执行，不依赖 Docker 或真实外部中间件。
- 集成测试：命名为 `*IT.java` 或 `*IntegrationTest.java`，由 `mvn -Pintegration-tests verify` 执行，优先使用本地确定性实现。
- 外部依赖测试：命名为 `*ExternalIT.java` 或 `*ExternalIntegrationTest.java`，由 `mvn -Pexternal-tests verify` 执行，可以连接 Kafka、MongoDB、Redis、PostgreSQL、Nacos 等真实或 Testcontainers 中间件。

本地无外部依赖验证命令：

```powershell
mvn test
mvn -Pquality verify
```

外部中间件验证命令占位：

```powershell
mvn -Pexternal-tests verify
```

## 7. 控制台命令

服务器控制台需要支持 cmd 输入，用于快速执行简单脚本或开发命令。

具体命令由业务扩展。
