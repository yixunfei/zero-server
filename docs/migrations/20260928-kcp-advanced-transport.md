# KCP 高级传输迁移说明

本分支直接升级到 ZKCP/ZKCI v1。旧客户端必须重新登录并消费新的 `KcpConnectInfo`；旧数据报布局、旧固定向量和旧保护策略 ID 不再兼容。

保护策略通过 `KcpTransportOptions` 选择：`0` 仅适合受控测试，`1` 为 HMAC-SHA256，`2` 为 ChaCha20-Poly1305，`3` 为 AES-GCM。策略可通过 `KcpAlgorithms` 注册自定义实现。方向密钥由票据密钥、conv、generation 和方向标签派生；票据轮换或代际变更才切换密钥，业务运行中不能热切换。

FEC 通过 `KcpFecOptions.none/xor/reedSolomon` 选择。低延迟业务使用小组和低 flush delay；移动弱网使用 Reed-Solomon；低频业务建议禁用 FEC。组缓存、恢复 CPU 和总字节都有硬上限，超过预算会丢弃该组并保持 KCP 顺序检查。

启用路径迁移时，新地址必须完成 challenge/response，验证前不改变路由。客户端调用 `KcpClient.rebind()` 后，旧路径继续工作到新路径确认，随后旧路径进入有限排空期。NAT 不能绕过票据或代际检查。

跨节点迁移使用 `KcpSessionStore`：源节点冻结并排空业务，提交不含密钥的 `KcpSessionSnapshot`，目标节点以更高 generation CAS 接管。票据密钥不能写入 Redis；应用必须通过既有 TLS 控制面向目标安全传递新票据并重建数据面。Redis 不可用时适配器 fail-closed，不得双主。

### Redis 本地验证

Redis 适配器是独立模块，不会被核心网络默认引入。测试环境使用本机 Docker `desktop-linux`，项目配置、数据和日志置于 `M:\docker_space`；本次使用 `zero-kcp-redis-20260928`（`redis://127.0.0.1:6388`）完成 Redis Lua 状态机集成测试，环境记录见[Redis 本地环境](../operations/kcp-redis-local.md)。

### 最终验证

`zero-net-kcp` 69 项、`zero-runtime-kcp` 6 项测试全部通过；相关 Reactor 18 模块的 Maven quality、架构守卫和框架边界守卫通过。独立示例通过 Checkstyle/PMD/SpotBugs，并实际运行 HMAC、ChaCha20-Poly1305、AES-GCM 与 NONE、XOR、Reed-Solomon 的 9 种组合，每组均完成换端口路径验证和 source -> target 会话迁移，generation 从 1 递增到 2。
