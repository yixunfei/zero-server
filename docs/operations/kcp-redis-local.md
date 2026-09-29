# KCP Redis 本地环境

仅在需要 Redis 端到端测试时创建独立 Compose 项目。先确认 `docker context show` 为 `desktop-linux`、引擎已连接，且 Docker Desktop 数据盘实际位于 `M:\docker_space`。本次验证使用项目 `zero-kcp-redis-20260928`，地址为 `redis://127.0.0.1:6388`，配置和持久化目录为 `M:\docker_space\containers\zero-kcp-redis-20260928` 与 `M:\docker_space\data\zero-kcp-redis-20260928`，不要复用其他项目容器或卷。

本地适配器使用 `RedisKcpSessionStore` 的单 key Lua 状态机，覆盖 owner、generation、lease、phase 和 snapshot。Redis 不可用时调用以异常完成；服务端应按 fail-closed 关闭数据面。本次 Redis Lua 状态机集成测试已通过，容器保持运行以便复核；停止服务使用该 Compose 项目的 `docker compose stop`，不要删除数据目录。
