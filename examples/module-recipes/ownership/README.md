# 租约、幂等提交与主动交接

覆盖模块：zero-ownership。依赖见 [pom.xml](pom.xml)，入口见 [OwnershipExample.java](src/main/java/group/zn/zero/examples/recipes/ownership/OwnershipExample.java) 的 `runFlow()`，测试见 [OwnershipExampleTest.java](src/test/java/group/zn/zero/examples/recipes/ownership/OwnershipExampleTest.java)。

## 运行

使用 JDK 21、Maven 3.9+，在仓库根目录执行。首次或框架源码更新后先运行 `mvn -B -ntp -DskipTests install`。

```powershell
mvn -B -ntp -f examples/module-recipes/ownership/pom.xml test exec:java
```

## 接入步骤

1. 租约和状态存储共用固定时钟，进程身份 A 取得 epoch=1。
2. 用 expectedVersion=0、request-001 提交 100；重放完整原请求不重复增加版本。
3. 新请求使用旧版本被拒绝；A 主动释放后 B 获取 epoch=2。
4. B 重新读取权威版本并提交 150；A 的新写入、旧请求重放以及释放均被拒绝。

## 预期结果

initialVersion=1，replayed=true，firstEpoch=1，secondEpoch=2，finalVersion=2，finalCoins=150，oldReleaseRejected=true。

`main` 打印 `Result` 后退出；回归测试断言关键结果，异常不会被仅打印后忽略。

## 接入边界

`InMemoryFencedStateStore` 与 `InMemoryOwnershipStore` 在本进程内原子校验，不能与远程租约服务混搭后宣称跨进程 fencing。这里是主动释放交接，不是宕机恢复或完整迁移协调。

payload 使用不可变值；存储不会深拷贝业务对象。原请求重放必须携带相同 expectedVersion 和 payload，且仍需当前有效租约。进程重启应生成新的 incarnationId，资源重新创建应更新 generation。

当前本地实现多类拒绝共享 `SYSTEM_ERROR`；示例通过具体操作与最终状态区分场景，不依赖异常消息作业务分支。收据有界，默认容量 4096；长期使用需设计归档策略，不能把幂等收据当成无限缓存。

## 扩展位置

替换到 PostgreSQL 事务实现时参考 [外部所有权接入](../../module-access/zero-ownership-postgresql.md)，应同时使用对应权威租约与状态存储并执行真实外部验证。

完整注释包含参数、结果、状态修改及线程边界；复制时保留组合根的资源关闭逻辑。返回的结果 record 和列表不可变。

返回 [七类示例索引](../README.md) 或 [全部示例](../../README.md)。
