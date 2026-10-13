# Li-Node01 生产适配

日常生产更新优先使用飞牛测试通过的发布镜像，不要求服务器保存后端源码。
配置仓库固定放到 `/opt/Software/li-group-config`，更新流程见其
`docs/fnos-to-li-node01.md`。下文的源码构建方法保留作为可选方式。

2026-10-13 已核对 `root@server.329509.xyz:233` 的实际容器。
后端仍使用 `/usr/local/group-center` 工作目录，数据库通过 `db:3306` 连接。
生产业务配置属于 `li-group-config`，不是本仓库的示例 `Config`。

## 从当前源码构建

`Dockerfile.source` 自动构建当前源码，不要求提前生成 JAR。
现有 `Dockerfile`、发布工作流和普通 `docker-compose.yml` 保留各自用途。

在装有 Docker 的机器上、后端仓库根目录执行：

```bash
cp .env.li-node01.example .env.li-node01
docker compose --env-file .env.li-node01 -f docker-compose.li-node01.yml config --quiet
docker compose --env-file .env.li-node01 -f docker-compose.li-node01.yml build core
```

`build core` 只构建镜像，不连接生产数据库，也不启动服务。
本机与服务器不同架构时应为服务器的实际架构构建镜像。
若在本机构建，镜像需要另行传到服务器；本次只准备配置，没有执行传输或发布。

生产默认设置：

| 项目 | 设置 |
| --- | --- |
| Compose 项目及服务 | `group-center`；`core`、`db` |
| 源码镜像名 | `group-center:li-node01`，可由 `GROUP_CENTER_IMAGE` 覆盖 |
| 数据根目录 | `/opt/Software/li-group-config/Docker/Server/group-center`，可由 `GROUP_CENTER_DEPLOY_DIR` 覆盖 |
| 业务配置 | 上述目录的 `Config` → `/usr/local/group-center/Config` |
| 缓存 / 日志 | `Cache` / `Data/Log` → 原容器目录 |
| MySQL 数据 / 备份 | `Data/Db/MySQL` / `Data/Db/Backup` → 原容器目录 |
| 端口 | 后端 `15090`、保留 `15092`；数据库宿主机 `15096` → 容器 `3306` |
| Agent 入口 | `REALTIME_AGENT_BASE_URL=http://g.329509.xyz` |

这些绑定目录要求已经存在；写错路径会使启动失败，避免创建空目录并误接一套新数据库。
数据库账号及默认密码与现有部署兼容。`GROUP_CENTER_DB_PASSWORD` 只用于指定现有密码，
不会修改已初始化 MySQL 的密码；不要把修改环境变量当成数据库密码迁移。
生产保留 `FileEnv.toml` 与机器人配置，不套用本地联调中关闭通知的设置。

## 更新现有应用

构建镜像后，建议继续从配置仓库更新服务，保留原来的管理入口：

```bash
cd /opt/Software/li-group-config/Docker/Server/group-center
GROUP_CENTER_IMAGE=group-center:li-node01 docker compose up -d --no-deps core
curl -fsS http://127.0.0.1:15090/version
```

上面是后续部署示例，本次没有执行。部署前备份现有 MySQL 与 Config，
并确认服务器已更新本次配置改动：Compose 应支持 `GROUP_CENTER_IMAGE`。
新版仍沿用生产 `spring.sql.init.mode=always`，启动会尝试执行建表 SQL，
不能把更换应用容器理解为数据库完全没有写入。
只更新 `core`，无需重建数据库；不要运行 `down -v`。
后续每次启动继续传入同样的镜像名，避免回到阿里云的 `latest`。
若使用了 `GROUP_CENTER_DB_PASSWORD` 覆盖，配置仓库启动时也需传入同样的已有数据库密码。

业务配置、用户资料、机器地址和密钥继续保留在生产挂载目录。
本仓库新增 `.dockerignore`，构建上下文排除这些运行数据和环境文件。
运行镜像从 `src/main/resources/application-docker.yml` 复制配置，
包含新版实时聚合、Bot、Proxy、Heartbeat 的基础设置。

## 当前服务器的问题

- 现有镜像 `/version` 返回 `1.8.8`，当前源码为 `1.8.9`。
- 现有容器没有 `REALTIME_AGENT_BASE_URL`，旧版镜像的外置 application.yml 也没有新版配置块。
- 7 个 GPU 代理正常；CPU 的 `/cpu/node01/`、`/cpu/server/` 当前没有有效代理，返回前端 HTML。
  本次没有猜测 CPU Agent 监听地址或端口。应确认实际 Agent 部署后再补规则。
- 生产 `CPU.yaml` 存在列表缩进错误；配置仓库本地版已有正确缩进，应随配置更新一并部署。

完整差异与检查结果见本地 `li-group-config/docs/li-node01-deployment-audit.md`。
