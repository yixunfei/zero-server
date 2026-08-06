# 本地 CSV 配置热重载示例

本示例展示 zeroServer 在不启动 Docker、不连接 Nacos/Kafka 的单进程模式下，如何完成游戏数值表的初始加载和运行期失败保旧：

```text
classpath items.csv
  -> 复制到临时目录
  -> Starter opt-in + 泛型 row decoder
  -> 全部候选验证成功后发布 v1
  -> 合法 CSV 单表原子替换为 v2
  -> 重复 key 的 v3 被拒绝
  -> 业务继续读取 v2
  -> 显式关闭 runtime 和受管执行器
```

## 运行

先在仓库根目录安装当前 SNAPSHOT，再执行示例：

```powershell
mvn -DskipTests install
mvn -f examples/config-hot-reload-local/pom.xml test
mvn -f examples/config-hot-reload-local/pom.xml exec:java
```

输出类似：

```text
config-hot-reload=ok|initialVersion=1|loadedVersion=2|rejectedVersion=2|name=Steel Sword|auditRecords=4
```

## 关键装配点

- `zero.config.hot-reload.enabled=true` 是显式开关，默认关闭。
- 使用 `ZeroRuntimeExecutors.localPrototype(...)`，保证 CSV IO 不在调用线程、Actor 或 Netty IO 线程内联执行。
- `ZeroConfigHotReloadFactory.configure(...)` 创建服务并追加到 runtime lifecycle。
- 业务通过 `ConfigTable<Integer, ItemConfig>` 读取当前不可变快照。
- `ConfigTableDefinition` 显式声明 key 列、必需列、key decoder 和泛型 row decoder。
- 手工 `reloadAsync(...)` 和可选 WatchService 共用同一套校验与原子发布路径。

真实项目通常不修改源码资源，而是把 CSV 放在部署目录或只读配置卷中。第一轮本地能力不提供跨文件事务、Nacos 通知、集群同步、远程上传、审批或签名校验；这些能力需要后续独立切片。
