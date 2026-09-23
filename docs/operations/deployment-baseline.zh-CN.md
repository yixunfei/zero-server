# Docker Compose 部署基线

本文提供 zeroServer 的可运行 Docker Compose 起点，用于受控开发、集成测试和部署方案评估。它不是生产发行物，也不代表生产就绪；当前仓库不提供通用的 app 镜像，`ZERO_APP_IMAGE` 必须由业务项目构建、扫描、签名并发布。

## 范围与边界

- Compose 文件位于 `deploy/docker-compose.yml`。
- 应用服务默认以非 root UID/GID、只读根文件系统、`no-new-privileges` 和全量 drop capabilities 运行。
- Redis、PostgreSQL 是可选的本地中间件 profile，不是 zeroServer 的强制依赖。Kafka、MongoDB、Nacos 等外部组件由项目自行提供和配置。
- Compose 只提供网络、资源上限、healthcheck、卷和 secret 路径占位；不提供 TLS/WAF/DDoS、身份系统、滚动发布、自动故障恢复或容量证明。
- 健康检查要求 app 镜像包含可执行的检查命令（默认使用 `wget`）以及 `GET /health`；项目应按自身 HTTP 入口覆盖 `ZERO_HEALTHCHECK_CMD`。

## 准备

```bash
cd zero-server
cp deploy/.env.example deploy/.env
mkdir -p deploy/secrets
printf '%s' 'replace-with-a-long-random-password' > deploy/secrets/postgres-password
printf '%s' 'project-config-placeholder' > deploy/secrets/zero-config
chmod 700 deploy/secrets
chmod 600 deploy/secrets/*
```

编辑 `deploy/.env`，至少替换 `ZERO_APP_IMAGE`。不要把真实密码、token、URI 或证书提交到仓库；`.env` 和 `deploy/secrets/` 下的真实文件应由部署系统或 secret manager 注入。

## 启动

仅启动 app（外部中间件由项目或平台提供）：

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d app
docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps
docker compose --env-file deploy/.env -f deploy/docker-compose.yml logs --tail=100 app
```

使用基线提供的可选 Redis/PostgreSQL：

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml --profile middleware up -d
docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps
```

默认端口只绑定到 `127.0.0.1`。如需由反向代理访问，应在受控网络中显式覆盖 `ZERO_BIND_ADDRESS`，并由项目补齐 TLS、鉴权和访问控制；不要直接将开发基线端口暴露到公网。

## 配置要点

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `ZERO_APP_IMAGE` | 必填 | 项目提供的 app 镜像，不使用仓库默认镜像 |
| `ZERO_BIND_ADDRESS` | `127.0.0.1` | 端口绑定地址 |
| `ZERO_HTTP_PORT` / `ZERO_GAME_PORT` | `8080` / `9000` | 宿主端口 |
| `ZERO_REDIS_URI` | `redis://redis:6379` | 可改为外部 Redis URI |
| `ZERO_POSTGRESQL_URL` | Compose 内地址 | 可改为外部 PostgreSQL URL |
| `ZERO_CPU_LIMIT` / `ZERO_MEMORY_LIMIT` | `2.0` / `1g` | app 容器资源上限，需按压测调整 |
| `ZERO_HEALTHCHECK_CMD` | `wget .../health` | 按项目镜像和健康端点覆盖 |

Compose 插值和 secret 文件路径由部署环境处理；密码不得通过命令行参数传递。若使用外部中间件，请使用项目的 Production Starter 配置和外部凭据管理，不要误以为本文件已经完成生产安全装配。

## 停止、更新与回滚

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml down
docker compose --env-file deploy/.env -f deploy/docker-compose.yml pull app
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d app
```

更新前记录当前镜像 digest、配置版本和数据备份；更新后确认 healthcheck、应用日志、关键业务探针和依赖健康。回滚应重新指定已验证的旧 digest，而不是使用浮动 `latest`。数据库 schema 迁移必须由项目提供可逆/兼容步骤，不能把 `down -v` 当作回滚。

## 验证清单

1. `docker compose config` 能解析，且 app 镜像和 secret 文件路径均已替换。
2. 容器用户不是 root，根文件系统保持只读，capabilities 未被意外恢复。
3. healthcheck 连续通过，日志不泄露 secret 或连接凭据。
4. 端口、网络出口、卷权限符合项目威胁模型。
5. 完成备份恢复演练、故障注入、升级/回滚验证和容量压测后，才能评估是否适合具体环境。

相关流程见[备份与恢复 Runbook](backup-recovery-runbook.zh-CN.md)。
