# telegram阅读epub工具

[可用bot](https://t.me/jp_readerBot)

## 使用 (可查看示图)

* 上传epub文件给bot
* bot转换成多页后发布到telegraph上，永久保存
* 通过链接访问阅读
* 点击书签即可记录当前阅读进度

## 部署

* 下载 `docker-compose.yml`
* 修改 `docker-compose.yml`
* 运行命令 `docker-compose up -d`

## 参数说明

| 参数名                         | 类型       | 默认值                        | 说明                                                                             |
|-----------------------------|----------|----------------------------|--------------------------------------------------------------------------------|
| `TELEGRAM_BOT_TOKEN`        | `string` | `null`                     | telegram bot token，通过`botFather`获取                                             |
| `TELEGRAM_BOT_USERNAME`     | `string` | `null`                     | telegram bot name                                                              |
| `TELEGRAM_BOT_ADMINS`       | `string` | `null`                     | bot的可上传书籍人的telegram id，多个使用`,`隔开，留空表示任何人都可上传epub文件                             |
| `TELEGRAM_BOT_BASE`         | `string` | `https://api.telegram.org` | telegram api base url                                                          |
| `TELEGRAPH_AUTHOR_NAME`     | `string` | `EpubReaderBot`            | telegraph 页面作者，任意即可                                                            |
| `TELEGRAPH_ACCESS_TOKEN`    | `string` | `null`                     | telegraph token，留空时会自动创建                                                       |
| `APP_CHARS_PER_PAGE`        | `int`    | `3000`                     | 每一页的字数，建议不超过10000，Telegraph 的 API 有 64KB 的节点大小限制                               |
| `TELEGRAPH_CATTO_PIC_TOKEN` | `string` | `null`                     | 自定义图床验证token(需自建)，具体参考：https://github.com/jp0id/CattoPic/blob/main/docs/API.md |

## 示图

<image src="https://raw.githubusercontent.com/jp0id/epub-bot/refs/heads/master/img/%E6%88%AA%E5%B1%8F2026-01-07%2013.41.40.png" width="600"></image>

<image src="https://raw.githubusercontent.com/jp0id/epub-bot/refs/heads/master/img/%E6%88%AA%E5%B1%8F2026-01-07%2013.41.47.png" width="600"></image>

<image src="https://raw.githubusercontent.com/jp0id/epub-bot/refs/heads/master/img/%E6%88%AA%E5%B1%8F2026-01-07%2013.40.58.png" width="600"></image>

<image src="https://raw.githubusercontent.com/jp0id/epub-bot/refs/heads/master/img/%E6%88%AA%E5%B1%8F2026-01-07%2013.43.01.png" width="600"></image>

<image src="https://raw.githubusercontent.com/jp0id/epub-bot/refs/heads/master/img/%E6%88%AA%E5%B1%8F2026-01-07%2013.46.44.png" width="600"></image>

<image src="https://raw.githubusercontent.com/jp0id/epub-bot/refs/heads/master/img/%E6%88%AA%E5%B1%8F2026-01-07%2013.41.07.png" width="600"></image>

## 贡献

欢迎提交 Issue 和 Pull Request！