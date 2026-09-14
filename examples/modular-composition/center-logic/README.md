# 中心—逻辑接口最小示例

在仓库根目录完成 `mvn -B -ntp -DskipTests install` 后执行：

```powershell
mvn -B -ntp -f examples/modular-composition/center-logic/pom.xml clean test exec:java
```

输出 `center-logic=ok|heartbeats=1`。应用只声明 bootstrap 和 RPC 两个集成依赖，无 Kafka、Nacos、Netty 或数据库。

`CenterRpc` 是两端共享接口，`CenterService` 是中心实现，`LogicService` 只通过注入的接口调用。
示例在一个进程中验证业务边界。进程拆分时，从 runtime 脚手架选择 `kafka`，服务端仍使用
`RpcServiceBinder`，客户端仍使用 `RpcClientFactory`；将各自的 transport/registry 注入相同业务类。
只有需要动态实例发现时才增加 `nacos`。详细配置和边界见 [场景接入指南](../../../docs/scenario-onboarding.zh-CN.md)。

此示例不包含网络心跳调度、节点租约、注册/摘除或多进程故障转移；本地完成一次 RPC 不代表这些治理能力已完成。
