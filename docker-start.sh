docker run -d \
  --name my-epub-bot \
  -e TELEGRAM_BOT_TOKEN="你的BotToken" \
  -e TELEGRAM_BOT_USERNAME="你的Bot用户名" \
  -e TELEGRAPH_AUTHOR_NAME="EpubReader" \
  -e APP_CHARS_PER_PAGE=3000 \
  epub-bot