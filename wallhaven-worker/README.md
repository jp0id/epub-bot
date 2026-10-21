# Wallhaven Image Bot Worker

这是一个与根项目完全独立的 Cloudflare Worker：每 30 分钟从 Wallhaven API 获取最新图片和最近一个月最热门图片，并使用一个新的 Telegram Bot 向配置的聊天发送每个信息流最多 3 张图片。

根项目的 Java Bot、原 `cloudflare/` Worker、根目录配置和部署文件不会被该 Worker 使用或修改。

## API 行为

Worker 调用：

```text
GET https://wallhaven.cc/api/v1/search
```

请求包含两个信息流：

- 最新图片：`sorting=date_added`、`order=desc`、`page=1`
- 最近一个月热门：`sorting=toplist`、`topRange=1M`、`order=desc`、`page=1`

两个信息流都使用 `categories=000` 和 `purity=000`，即关闭所有分类和纯度限制。Wallhaven API 对 NSFW 结果要求有效的 API key。

Wallhaven 返回结果按对应信息流排序。Worker 默认使用 `path` 下载原图（高清版本），找不到时回退到 `thumbs.large` 或 `thumbs.original`。随后使用每张图片的 `/api/v1/w/<ID>` 详情接口补充 tags、上传时间、类别、纯度、分辨率和文件格式等信息，并放入 Telegram caption。每个信息流默认发送 3 张。API key 是可选的，仅通过 `X-API-Key` 请求头发送。每轮共进行 2 次搜索请求，最多 6 次详情请求，低于 Wallhaven 每分钟 45 次调用限制。

## 配置

必须配置：

- `WALLHAVEN_TELEGRAM_BOT_TOKEN`：通过 BotFather 创建的新 Bot token，建议使用 Wrangler Secret。
- `WALLHAVEN_TELEGRAM_CHAT_IDS`：逗号分隔的目标 chat ID。Bot 必须已经加入目标群组/频道，并拥有发送图片权限。

可选配置在 `wrangler.toml` 的 `[vars]` 中提供默认值：

- `WALLHAVEN_API_KEY`
- `WALLHAVEN_CATEGORIES`（默认 `000`，关闭所有分类限制）
- `WALLHAVEN_PURITY`（默认 `000`，关闭所有纯度限制；NSFW 仍需要 API key）
- `WALLHAVEN_IMAGES_PER_RUN`（最新信息流默认 5，代码限制最大 10）
- `WALLHAVEN_TOP_IMAGES_PER_RUN`（最近一个月热门信息流默认 3，代码限制最大 10）
- `WALLHAVEN_IMAGE_SOURCE`：默认 `path`，即 Wallhaven 原图；也支持 `large`、`original`。原图可能更大，受 `WALLHAVEN_MAX_IMAGE_BYTES` 限制。
- `WALLHAVEN_MAX_IMAGE_BYTES`（默认 10 MB）
- `WALLHAVEN_SENT_TTL_SECONDS`（默认 30 天）

KV key 使用 `sent:<feed>:<chat-id>:<wallhaven-image-id>`，其中 feed 为 `latest` 或 `toplist-1M`。只有 Telegram `sendPhoto` 明确返回 `ok: true` 后才写入 KV，因此失败的投递会在之后的轮次重试。两个信息流使用独立去重记录；如果同一张图片同时出现在最新和热门结果中，会分别推送一次。Telegram 已接受请求但 Worker 在响应前超时的极端场景可能产生重复，这是 Bot API 无法避免的至少一次投递语义。

## 部署

需要先安装 Node.js/npm，并完成 Wrangler 登录：

```bash
cd wallhaven-worker
npm install
npx wrangler login
```

创建独立 KV Namespace：

```bash
npx wrangler kv namespace create WALLHAVEN_DELIVERIES
```

把命令返回的 namespace ID 填入 `wrangler.toml` 的 `[[kv_namespaces]]`，替换：

```toml
id = "REPLACE_WITH_WALLHAVEN_DELIVERIES_KV_ID"
```

设置新 Bot token：

```bash
npx wrangler secret put WALLHAVEN_TELEGRAM_BOT_TOKEN
```

设置聊天 ID（作为 secret 或在 vars 中配置均可；聊天 ID 通常不属于凭据）：

```bash
npx wrangler secret put WALLHAVEN_TELEGRAM_CHAT_IDS
```

部署：

```bash
npm run typecheck
npx wrangler deploy
```

配置普通变量可以修改新 Worker 自己的 `wrangler.toml`，也可以使用 Wrangler 的环境配置。不要修改根项目的 `cloudflare/wrangler.toml`。

## 本地验证

```bash
npm run typecheck
npx wrangler dev --test-scheduled
```

`fetch()` 仅返回健康状态；正常发送只由 Cron `scheduled()` 触发，避免公开 HTTP 入口被重复调用。部署后可在 Cloudflare Worker 日志中查看 `latest` 和 `toplist-1M` 两个信息流的请求及发送结果，日志不会输出 token 或 API key。

## 注意事项

- Telegram 的新 Bot token 不能与原 epub Bot token 相同。
- 目标频道需要把 Bot 加入为管理员并授予发消息/发图片权限。
- `WALLHAVEN_API_KEY` 不放进 URL，避免出现在访问日志中。
- 图片下载会检查 HTTP 状态、Content-Type 和最大字节数；超过限制的图片会跳过，不会写入成功去重记录。
- 原项目仍使用自己的 `cloudflare/` Worker 和配置，两个 Worker 需要分别部署。
