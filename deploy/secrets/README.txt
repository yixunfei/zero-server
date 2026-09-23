# Secret placeholders

该目录只保留路径占位，不应提交真实凭据。运行前创建以下文件，并限制为部署用户可读：

- `zero-config`：项目提供的应用配置（可为空，仅当应用不要求该文件时）。
- `postgres-password`：仅用于启用 Compose 内置 PostgreSQL 时的数据库密码。

建议：

```bash
mkdir -p deploy/secrets
chmod 700 deploy/secrets
printf '%s' 'replace-me' > deploy/secrets/postgres-password
chmod 600 deploy/secrets/postgres-password
```

请把真实 secret 放在外部 secret manager、受控主机或未纳入版本控制的路径；不要将其写入 `.env`、镜像层、日志或仓库。
