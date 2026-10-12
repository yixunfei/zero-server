# GM/REST 预演、授权、幂等与审计

覆盖模块：zero-gm、zero-gm-rest。依赖见 [pom.xml](pom.xml)，入口见 [GmExample.java](src/main/java/group/zn/zero/examples/recipes/gm/GmExample.java) 的 `runFlow()`，测试见 [GmExampleTest.java](src/test/java/group/zn/zero/examples/recipes/gm/GmExampleTest.java)。

## 运行

使用 JDK 21、Maven 3.9+，在仓库根目录执行。首次或框架源码更新后先运行 `mvn -B -ntp -DskipTests install`。

```powershell
mvn -B -ntp -f examples/module-recipes/gm/pom.xml test exec:java
```

## 接入步骤

1. 注册 `/announce <server> <text>`；`AnnouncementCommand` 只修改本地计数。
2. 组合固定可信身份、角色/权限、回环 IP 白名单、fixture 审批及内存幂等存储。
3. 构造 `HttpRequest` 并通过真实 `GmRestTransportAdapter` → `GmOperationEndpoint` → executor。
4. 顺序验证预演、执行、完全相同请求重放、同键异参、目标不符、审批不符、权限不足和身份缺失。

## 预期结果

预演/执行/重放 HTTP=200；同键异参=409；目标/审批/权限拒绝=403；身份缺失=401；afterPreview=0，executions=1，commitState=COMMITTED。

`main` 打印 `Result` 后退出；回归测试断言关键结果，异常不会被仅打印后忽略。

## 接入边界

本例不监听 HTTP 端口，不向玩家发送公告。固定身份、IP 和审批 token 都是本地 fixture；部署时必须接入可信认证/审批服务，不能从请求自声明字段生成权限。本例固定 traceId 只是演示，真实入口应接入可信请求上下文并校验一致性。

`dryRun` 是 JSON 必填布尔值，预演同样需要授权与审批。响应只公开 `count`，默认隐藏自由数据和消息。审计记录放在本次内存列表，不能替代耐久审计后端。401、JSON 格式错误发生在 endpoint 前，应由 HTTP 宿主另行记录入口安全审计。

内存幂等只覆盖当前 JVM，终态保留期为框架默认 10 分钟；多进程与长期重试须使用一致的部署指纹策略、共享幂等后端及业务提交协议。提交状态为 UNKNOWN/COMMITTED 时不得盲重试。

## 扩展位置

从 [AnnouncementCommand.java](src/main/java/group/zn/zero/examples/recipes/gm/AnnouncementCommand.java) 替换业务处理器；玩家状态修改应转交玩家 Actor，并设计业务提交与持久审计/幂等的失败处理。

完整注释包含参数、结果、状态修改及线程边界；复制时保留组合根的资源关闭逻辑。返回的结果 record 和列表不可变。

返回 [七类示例索引](../README.md) 或 [全部示例](../../README.md)。
