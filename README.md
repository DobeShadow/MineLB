# MineLeaderboards

定时采集服务器四类数据并推送到 MineMC 排行榜端点的 Bukkit 插件。

## 功能

每 60 秒（可配置）采集以下四个榜单，按 JSON 契约 POST 到
`https://www.minemc.asia/leaderboard-ingest.php`：

| 榜单 | key | 数据来源 | 单位 |
|------|-----|----------|------|
| 财富榜 | wealth | Vault 经济（PAPI 兜底） | ¥ |
| 在线时长榜 | playtime | Bukkit 统计 / stats 文件 | 小时 |
| 决斗积分榜 | duel | PlaceholderAPI | 分 |
| 签到榜 | signin | PAPI / YAML / SQL 可切换 | 次 |

## 环境要求

- Purpur / Paper / Spigot 1.20.x
- Java 17+
- 软依赖：Vault（财富榜）、PlaceholderAPI（决斗/签到榜）

## 安装

1. 将 `MineLeaderboards-1.0.0.jar` 放入服务器 `plugins/` 目录
2. 启动服务器，生成 `plugins/MineLeaderboards/config.yml`
3. 按需修改配置（尤其是占位符名）
4. 重启服务器或 `/mineleaderboards reload`

## 命令

主命令 `/mineleaderboards`，别名 `/minelb`、`/mlb`。

| 命令 | 说明 | 权限 |
|------|------|------|
| `/mineleaderboards reload` | 重载配置并重建任务 | `mineleaderboards.admin` |
| `/mineleaderboards push` | 立即推送一次 | `mineleaderboards.admin` |
| `/mineleaderboards test <board>` | 采集指定榜单并打印 top10 到控制台 | `mineleaderboards.admin` |

`<board>` 可选：`wealth` / `playtime` / `duel` / `signin`

## 配置要点

- `leaderboard.token`：排行榜端点专用 token，与在线玩家推送的 token 不同
- `leaderboard.skip-tls-verify`：当前目标站点证书已过期，需保持 `true`；
  证书修复后请改回 `false`
- `leaderboard.boards.duel.placeholder`：ASDuel 积分占位符，默认 `asduels_points`
- `leaderboard.boards.signin.placeholder`：LiteSignIn 签到次数占位符，默认 `litesignin_statistics`
- `leaderboard.boards.signin.provider`：可切换 `papi` / `yaml` / `sql`

## 排查

- 推送失败先看控制台日志里的 HTTP 状态码
- 数据为空先用 `/mineleaderboards test <board>` 确认数据源能否取到值
- 占位符名不确定时，用 `/papi parse <玩家> %占位符%` 验证

## 注意事项

- 签到榜数值是「签到总次数」，而网页侧单位标签写死为「天」，
  如需一致请修改服务端 `leaderboard-ingest.php` 的 BOARD_META
- 每次推送会整份覆盖 `leaderboard-cache.json`，四个榜都会带上（无数据传空数组）
