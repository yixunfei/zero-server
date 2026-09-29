# Java KCP 开箱即用示例

独立 main 应用，复用真实 production TCP 生命周期、TLS 证书校验、KcpRuntime、KcpClient 和 KcpRecovery。
无需数据库、容器或公网。仅绑定本机，必须显式传入 --demo；临时证书和 demo-secret 不能用于实际账号服务。

## 直接运行全部场景

在仓库根目录使用 Java 21：

~~~powershell
$env:JAVA_HOME='D:\env\jdk21'
.\mvnw.cmd -pl zero-bom,zero-runtime-kcp -am -DskipTests install
.\mvnw.cmd -f examples/kcp-tcp-login/pom.xml compile exec:java '-Dexec.args=--demo ALL'
~~~

每个场景依次执行 TLS 登录 → 下发连接描述 → KCP 业务回显 → 新票据恢复 → TCP 回退 → 资源关闭。
成功输出五条 PASS，最后服务器 sessions、connected、pendingSendBytes、pendingInboundBytes 为 0。
将 ALL 改为 LOW_LATENCY、BALANCED、MOBILE、LOW_FREQUENCY 或 BULK 可只运行一种。
Maven 的 install 只更新本机依赖缓存，不发布远端制品。

## 分开运行两端

服务端默认存活 60 秒，可改为 1..600 秒：

~~~powershell
.\mvnw.cmd -f examples/kcp-tcp-login/pom.xml compile exec:java '-Dexec.mainClass=group.zn.zero.examples.kcp.KcpDemoServer' '-Dexec.args=--demo MOBILE 60'
~~~

复制 READY 中的 TCP 端口和公钥证书路径，在另一个终端启动客户端：

~~~powershell
.\mvnw.cmd -f examples/kcp-tcp-login/pom.xml exec:java '-Dexec.mainClass=group.zn.zero.examples.kcp.KcpDemoClient' '-Dexec.args=--demo <TCP_PORT> <CERTIFICATE_PATH>'
~~~

客户端只信任该证书并校验 localhost。证书路径有空格时需按 Maven exec 参数规则为路径加引号。
服务器退出删除临时证书。不要输出或复制其私钥文件；示例不会把密钥放入日志或 UDP。

## 对应业务

- LOW_LATENCY：小型 tick/input 命令。
- BALANCED：发送前已经聚合的房间/AOI 状态。
- MOBILE：新票据后恢复请求。
- LOW_FREQUENCY：大厅 ready 通知；自动心跳由客户端维护。
- BULK：32KB 有限数据块；大资源和敏感数据应使用 TLS TCP。

这些是传输接入示例，不代替游戏领域模型、事务去重、断线快照或业务 Actor。
业务协议 ID=1/100/101/200..202 只属于本示例，框架不保留它们。
真实项目替换身份服务、replay provider、证书和控制面协议；业务不能伪造认证属性。

[场景/配置指南](../../docs/guides/kcp-scenarios.zh-CN.md) · [协议契约](../../docs/reference/kcp-transport-contract.zh-CN.md)

## 高级传输组合

使用独立入口验证可选保护、FEC、换端口路径验证和双节点会话接管：

~~~powershell
.\mvnw.cmd -f examples/kcp-tcp-login/pom.xml compile exec:java `
  '-Dexec.mainClass=group.zn.zero.examples.kcp.KcpAdvancedDemoApplication' `
  '-Dexec.args=--demo AES RS'
~~~

参数组合为 `HMAC|CHACHA|AES` 与 `NONE|XOR|RS`。省略参数运行全部九种组合。
示例在源节点完成 `quiesce -> migrate`，目标节点以更高 generation `claim`，并在数据面重建新客户端；票据密钥只通过 TLS 控制连接传输，Redis 或其他外部 SPI 可替换 `InMemoryKcpSessionStore`。
示例仅绑定本机，不能作为公网容量、真实 NAT 设备兼容性或跨语言互操作证明。

## 自动验证

~~~powershell
.\mvnw.cmd -pl zero-runtime-kcp -am -Pquality verify
.\mvnw.cmd -f examples/kcp-tcp-login/pom.xml -Pquality verify
~~~

弱网矩阵使用固定种子、虚拟时间和双向丢包；实际 TLS/UDP 联调另外执行。
KcpWorkloadTest 输出本机串行回显分位数；KcpOptimizationTest 输出当前认证路径线程分配量。
不能把回环每秒消息数当成服务器并发容量。完整证据见迁移说明。
