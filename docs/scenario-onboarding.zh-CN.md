# 按服务器形态开始开发

先选择业务形态，再选择所需基础设施。`zero-server-starter` 与 `zero-server-starter-production` 是全量便利组合；需要小依赖时使用独立集成模块或 `runtime` 脚手架。

## 选择入口

| 我现在需要 | 入口 | 实际边界 |
| --- | --- | --- |
| 空白单体内核 | `runtime`，不指定 components | 3 个框架制品：core/runtime/bootstrap；无线程池、网络、数据库 |
| 写玩家、场景业务原型 | `local` | 生成协议、BO、登录和移动闭环；默认无外部服务 |
| 本地验证中心与逻辑业务接口 | `examples/modular-composition/center-logic` | 实际本地 RPC 调用，两个直接集成依赖；一个进程运行 |
| 把中心与逻辑拆成进程 | `runtime --components kafka` | 15 个框架制品；自动补日志；不引入 Nacos 或数据库，需 Kafka |
| 增加动态服务发现 | 额外选择 `nacos` | 提供发现与 RPC resolver；业务仍需显式注册服务、绑定客户端路由 |
| 组合分布式基础设施 | `runtime --components kafka,nacos,mongo,redis,postgresql` | 30 个框架制品；只诊断不自动连接；并不等于完整治理服务器 |

上述制品数是 Maven 实际解析到的 `group.zn.zero` 制品数量，不是全部第三方 JAR 数量。选择 Nacos 不会自动引入 Kafka；选择 MongoDB/PostgreSQL 不会引入 Redis。

## 1. 第一次准备

在仓库根目录使用 Java 21 和 Maven 3.9+：

```powershell
java scripts/ZeroLocalDoctor.java
mvn -B -ntp -DskipTests install
```

当前是源码分发的 SNAPSHOT，首次需要安装框架工件。全仓构建不表示生成应用运行时依赖全仓。

## 2. 单体原型：生成后直接改业务

```powershell
java scripts/NewLocalGame.java --template local --projectName my-game --outputDir target/my-game
mvn -q -f target/my-game/pom.xml clean test exec:java
```

先读生成工程 `BUSINESS_GUIDE.md`，从 `.si` 和 BO 实现开始。需要空白内核则把 `--template local` 换成 `--template runtime`；需要 Actor/事件时添加 `--components actor,event`。

这些命令执行一次 smoke 并关闭资源。要验证客户端真实发包，使用已有 TCP 示例：

```powershell
mvn -q -f examples/rpg-tcp-generated/pom.xml clean test exec:java
```

TCP 示例监听本机临时端口、收发一个真实协议请求后退出。业务线程来自 `ZeroRuntimeExecutors`；协议生成工具只在 Maven 插件类路径内。长期运行的监听入口、协议认证、断线重连和调度由应用显式组装。

## 3. 中心—逻辑：先写接口，再换传输

先跑不需要中间件的完整业务调用：

```powershell
mvn -q -f examples/modular-composition/center-logic/pom.xml clean test exec:java
```

入口中的 `CenterRpc` 是共享契约，`CenterService` 在中心实现，`LogicService` 只接收该接口；`RpcServiceBinder` 绑定实现，`RpcClientFactory` 创建客户端。默认本地传输验证一次异步心跳，输出 `heartbeats=1`。

准备真实进程间传输时，分别生成中心和逻辑的装配工程：

```powershell
java scripts/NewLocalGame.java --template runtime --components kafka --projectName center --outputDir target/center
java scripts/NewLocalGame.java --template runtime --components kafka --projectName logic --outputDir target/logic
mvn -q -f target/center/pom.xml clean test exec:java
mvn -q -f target/logic/pom.xml clean test exec:java
```

这两次 smoke 只诊断配置，不连接 Kafka。把同一接口和各自业务放入对应工程，仍使用原绑定器/代理工厂；从 runtime 的 `RpcRuntime.RPC_HANDLER_REGISTRY` / `RPC_TRANSPORT` 取得所选实现。

按各工程的配置样例填写 Kafka 地址。两进程使用不同 `client-id`、`consumer-group-id` 和 `reply-topic`；topic 路由必须一致。静态部署不需要 Nacos；添加 `nacos` 后仍需按 [RPC 文档](rpc.zh-CN.md) 绑定 resolver 和发布实例。框架当前正式远程 RPC Adapter 是 Kafka，尚无免 broker 的 TCP RPC Adapter。

## 4. 可选基础设施：先诊断，再启动

```powershell
java scripts/NewLocalGame.java --template runtime --components kafka,nacos,mongo,redis,postgresql --projectName services --outputDir target/services
mvn -q -f target/services/pom.xml clean test exec:java
```

生成内容包括精简 POM、`RuntimeAssembly.java`、组件清单及仅针对所选 Adapter 的配置样例。普通 `test`/运行不创建客户端；地址、用户名、密码通过外部配置或 Adapter 支持的环境变量提供。PostgreSQL 样例的账号密码为空，必须自行填写。

把样例复制到自己管理的文件，设置绝对路径：

```powershell
$env:ZERO_CONFIG_FILE='D:\my-game-config\services.properties'
mvn -q -f target/services/pom.xml exec:java '-Dexec.args=--diagnose'
mvn -q -f target/services/pom.xml exec:java '-Dexec.args=--start'
```

`runtime-diagnosis=incomplete` 表示缺配置；`ok` 表示配置和组件图通过校验。两者都不证明连接成功。`--start` 才创建并启动所选 Adapter，执行真实健康检查，然后关闭此 smoke 的资源；业务服务的长期运行入口需由应用维护。

配置中的 `zero.mode` 支持 `standalone`、`external-test`、`production`，外部模板默认 `external-test`。代码直接装配也可以使用：

```java
var assembly = ProductionAssembly.builder("standalone", config)
        .install(RedisRuntime.module());
var report = assembly.diagnose(); // 汇总缺失键，不创建资源
var plan = assembly.plan();       // 要求配置齐全，返回实际 provider 图
```

`standalone` 不自动启用任何基础设施。网络生命周期仍需显式策略、认证链和非内联 remote IO 执行器。`NetworkRuntime` 管理连接生命周期，不会自动创建 TCP listener；脚手架暂不提供 `net` 组件，不生成默认放行策略。

## 5. 组合与替换规则

- `kafka` 替换同次选择中的本地 `rpc`，`nacos` 替换本地 `discovery`；生成清单只记录有效实现。
- `redis` 表示 Redis 数据来源；同时选 `cache` 仍是本地缓存。Redis 分布式缓存需显式 codec 与 cache 开关，见 [按需装配](modular-composition-guide.zh-CN.md)。
- 一个数据来源自动提供角色 `main`。多个来源按来源名提供角色：`local`、`redis`、`mongo`、`postgresql`，不隐式猜测哪个存玩家数据。可在生成的 `DataRuntime.repositories(...)` 中改为 `game -> mongo`、`platform -> postgresql` 等业务角色。
- 业务对象通过构造器接收 `Repository`、RPC 接口、Actor 或 EventBus；只在装配入口调用 runtime capability API，避免业务层到处查容器。
- typed Provider 配置从 `context.config()` 读取。`configSource(...)` 只覆盖声明过的 typed schema；`RuntimeBasics.CONFIG` 的其他业务字符串来自原 `ZeroConfig`，不会自动汇总所有来源。
- 七类业务模板仍默认运行本地业务 smoke；给它们额外选择真实 Adapter 后，`runDemo()`/测试需要相应真实配置和服务。需要无资源诊断工程时使用 `runtime` 模板。

完整分布式治理仍有安全入口、周期恢复、可靠持久化、观测运营和容量证据缺口。当前修复与后续验收顺序见 [本轮审查与优化方案](scenario-audit.zh-CN.md)。
