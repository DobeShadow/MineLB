# MineLeaderboards

定时采集服务器四类数据并推送到 MINEMC 排行榜端点的 Bukkit 插件。

## 功能

每 60 秒（可配置）采集以下四个榜单，按 JSON 契约 POST 到
`https://www.minemc.asia/leaderboard-ingest.php`，端点写入
`leaderboard-cache.json`，`https://www.minemc.asia/leaderboards` 读取展示。

| 榜单 | key | 数据来源 | 单位 |
|------|-----|----------|------|
| 服务器财富榜 | wealth | Vault 经济（PAPI 兜底） | ¥ |
| 在线时长榜 | playtime | Bukkit `PLAY_ONE_MINUTE` / stats 文件 | 小时 |
| 决斗积分榜 | duel | PlaceholderAPI（ASDuel） | 分 |
| 连续签到榜 | signin | PlaceholderAPI（LiteSignIn `%litesignin_continuous%`） | 天 |

Vault / PlaceholderAPI 全部通过反射调用，**不需要编译期依赖**。

## 环境要求

- Purpur / Paper **1.21.11**（`api-version: '1.21'`）
- **Java 21+**
- 软依赖：Vault（财富榜）、PlaceholderAPI（决斗 / 签到榜）

## 构建

```bash
mvn clean package
# 产物：target/MineLeaderboards-1.0.2.jar
```

只依赖 `paper-api`（scope provided），不打包第三方库。

## 安装

1. 将 `MineLeaderboards-1.0.2.jar` 放入服务器 `plugins/`
2. 启动服务器，生成 `plugins/MineLeaderboards/config.yml`
3. 按服务器实际占位符名修改配置（尤其是 duel / signin）
4. 由管理员手动重启或 `/mineleaderboards reload`（插件不会自己重启服务器）
5. `minelb test wealth` 等命令确认四个榜都能取到值，再 `minelb push`

## 命令

主命令 `/mineleaderboards`，别名 `/minelb`、`/mlb`。

| 命令 | 说明 | 权限 |
|------|------|------|
| `/mineleaderboards reload` | 重载配置、重建候选池 / HTTP 客户端 / 定时任务 | `mineleaderboards.admin` |
| `/mineleaderboards push` | 立即推送一次 | `mineleaderboards.admin` |
| `/mineleaderboards test <board>` | 采集指定榜单并打印 top10 到控制台 | `mineleaderboards.admin` |

`<board>` 可选：`wealth` / `playtime` / `duel` / `signin`

## 配置要点

- `leaderboard.token`：排行榜端点专用 token，与在线玩家状态推送（MineStatus）的 token 不同
- `leaderboard.skip-tls-verify`：当前 `www.minemc.asia` 证书已过期，需保持 `true`；
  证书修复后改回 `false`
- `leaderboard.boards.duel.placeholder`：ASDuel 积分占位符，默认 `asduels_points`，
  上线前用 `/papi parse <玩家> %asduels_points%` 验证
- `leaderboard.boards.signin.placeholder`：LiteSignIn **连续签到**占位符，
  默认 `litesignin_continuous`；`litesignin_statistics` 是累计总次数，别混用
- `leaderboard.boards.*.allow-offline`：默认 `false`，PAPI 榜只统计在线玩家。
  如果榜单人数不足 10，可改成 `true` 后用 `minelb test <board>` 验证扩展是否支持
  离线玩家查询；不支持时会返回未解析的 `%xxx%`，插件会自动丢弃
- `leaderboard.heads.source`：默认 `none`（插件不下载头像，网页侧兜底）。
  服务器能访问 mc-heads.net 时可改 `auto`（内存 → 磁盘 → 外网下载）。
  **注意 `saveDefaultConfig()` 不会覆盖已有的 config.yml**，从旧版升级时要手动同步这一项

## 排查

- 推送失败先看控制台日志里的 HTTP 状态码 **和响应体**，成功是 **204**（不是 200）
- `HTTP 400 {"ok":false,"error":"invalid_json"}` = 推送的 JSON 非法。
  1.0.2 已修复 `buildJson()` 每个 board 多关一个 `}` 导致 boards 被提前关闭的问题，
  并在推送前加了括号配对自检；遇到 400 先确认不是旧版 jar
- `HTTP 403 invalid_token` = token 不对；`405` = 用了 GET；`413` = 请求体超 512KB
- 数据为空先用 `minelb test <board>` 确认数据源能否取到值
- 占位符名不确定时，用 `/papi parse <玩家> %占位符%` 验证
- 头像服务连续失败 3 次后，插件会静默跳过外网下载 10 分钟，避免一轮推送卡死；
  此时前端会用自己的头像兜底
- 手动 `minelb push` 撞上定时任务时的「跳过本轮」已降级为 fine 日志，不再刷屏
- **endpoint 建议保持 `https://`**：实测 http 也能通，但 token 会明文过公网、
  80 端口出方向不稳定；证书过期用 `skip-tls-verify: true` 处理，不要改用 http 绕过

## 与网页侧的契约

```json
{
  "time": 1794385805,
  "boards": {
    "wealth":   {"entries": [{"name":"haiop","uuid":"...","value":128450.5,"head":"data:image/png;base64,..."}]},
    "playtime": {"entries": []},
    "duel":     {"entries": []},
    "signin":   {"entries": []}
  }
}
```

- 四个 key 每次都要带上，无数据传空数组（端点整份覆盖缓存）
- `value` 必须是 JSON number，不能是格式化字符串
- `head` 可以是 `data:image/...;base64,...` 或空字符串，长度上限 8192 字符
- `name` / `unit` / `format` / `desc` 由端点白名单固定，插件不需要传
- 端点按 `value` 倒序重排，每榜最多 20 条，网页显示前 10

## 注意事项

- 每次推送会整份覆盖 `leaderboard-cache.json`
- 候选池默认只扫主世界 `playerdata/*.dat` + 在线玩家，上限 400 人、
  只看近 180 天活跃玩家，避免老服遍历几万条 usercache 卡主线程
- `skip-tls-verify: true` 会跳过该链路的证书校验，存在中间人风险，
  属于证书过期期间的临时方案
