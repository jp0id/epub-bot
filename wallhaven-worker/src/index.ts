interface Env {
  DELIVERIES: KVNamespace
  WALLHAVEN_TELEGRAM_BOT_TOKEN?: string
  WALLHAVEN_TELEGRAM_CHAT_IDS?: string
  WALLHAVEN_TELEGRAM_API_BASE?: string
  WALLHAVEN_TELEGRAM_WEBHOOK_SECRET_TOKEN?: string
  WALLHAVEN_API_URL?: string
  WALLHAVEN_API_KEY?: string
  WALLHAVEN_CATEGORIES?: string
  WALLHAVEN_PURITY?: string
  WALLHAVEN_IMAGES_PER_RUN?: string
  WALLHAVEN_TOP_IMAGES_PER_RUN?: string
  WALLHAVEN_IMAGE_SOURCE?: string
  WALLHAVEN_MAX_IMAGE_BYTES?: string
  WALLHAVEN_SENT_TTL_SECONDS?: string
  WALLHAVEN_COMMAND_COOLDOWN_SECONDS?: string
}

interface WallhavenTag {
  name?: string
  alias?: string
}

interface WallhavenImage {
  id: string
  url?: string
  path?: string
  file_type?: string
  created_at?: string
  category?: string
  purity?: string
  dimension_x?: number
  dimension_y?: number
  resolution?: string
  ratio?: string
  file_size?: number
  tags?: WallhavenTag[]
  thumbs?: {
    large?: string
    original?: string
    small?: string
  }
}

interface WallhavenSearchResponse {
  data?: WallhavenImage[]
  meta?: {
    current_page?: number
    last_page?: number
    seed?: string
  }
}

interface ImageRequestOptions {
  categories?: string
  query?: string
  atleast?: string
  ratio?: string
  excludeIds?: Set<string>
}

interface ImageCommand {
  sorting: string
  topRange?: string
  categories?: string
  query?: string
  atleast?: string
  ratio?: string
  random?: boolean
}

interface WallhavenDetailResponse {
  data?: WallhavenImage
}

interface TelegramResponse {
  ok?: boolean
  description?: string
  result?: unknown
}

interface TelegramUpdate {
  message?: {
    chat?: {
      id?: number | string
    }
    text?: string
  }
}

class WallhavenApiError extends Error {
  constructor(readonly status: number) {
    super(`Wallhaven HTTP ${status}`)
    this.name = 'WallhavenApiError'
  }
}

const RANDOM_IMAGES_PER_COMMAND = 3
const RECENT_RANDOM_TTL_SECONDS = 86_400
const SEARCH_QUERY_TTL_SECONDS = 300
const MAX_SEARCH_QUERY_LENGTH = 200
const TELEGRAM_MAX_PHOTO_BYTES = 10_000_000

const DEFAULTS = {
  telegramApiBase: 'https://api.telegram.org',
  wallhavenApiUrl: 'https://wallhaven.cc/api/v1/search',
  categories: '000',
  purity: '000',
  imagesPerRun: 3,
  imageSource: 'path',
  maxImageBytes: 10_000_000,
  sentTtlSeconds: 2_592_000,
  commandCooldownSeconds: 10,
} as const

export default {
  async fetch(
    request: Request,
    env: Env,
    ctx: ExecutionContext,
  ): Promise<Response> {
    if (request.method === 'GET') {
      return json({
        service: 'wallhaven-image-bot',
        status: 'ok',
        schedule: '*/30 * * * *',
      })
    }

    if (request.method === 'POST') {
      return handleTelegramWebhook(request, env, ctx)
    }

    return json({ error: 'Method not allowed' }, 405)
  },

  async scheduled(
    _controller: ScheduledController,
    env: Env,
    ctx: ExecutionContext,
  ): Promise<void> {
    ctx.waitUntil(runScheduled(env))
  },
}

async function handleTelegramWebhook(
  request: Request,
  env: Env,
  ctx: ExecutionContext,
): Promise<Response> {
  const expectedSecret = env.WALLHAVEN_TELEGRAM_WEBHOOK_SECRET_TOKEN?.trim()
  if (expectedSecret && request.headers.get('X-Telegram-Bot-Api-Secret-Token') !== expectedSecret) {
    return json({ error: 'Unauthorized' }, 401)
  }

  let update: TelegramUpdate
  try {
    update = (await request.json()) as TelegramUpdate
  } catch {
    return json({ error: 'Invalid JSON' }, 400)
  }

  const text = update.message?.text?.trim()
  const chatId = update.message?.chat?.id
  if (chatId === undefined || !text) {
    return json({ ok: true })
  }
  console.log(`Received Telegram command from chat ${chatId}: ${text}`)

  const allowedChatIds = parseChatIds(env.WALLHAVEN_TELEGRAM_CHAT_IDS)
  if (!allowedChatIds.includes(String(chatId))) {
    console.warn(`Ignoring Telegram update from unauthorized chat ${chatId}`)
    return json({ ok: true })
  }

  const token = env.WALLHAVEN_TELEGRAM_BOT_TOKEN?.trim()
  if (!token) {
    console.error('Missing WALLHAVEN_TELEGRAM_BOT_TOKEN')
    return json({ error: 'Bot is not configured' }, 500)
  }

  const apiBase = normalizeTelegramApiBase(env.WALLHAVEN_TELEGRAM_API_BASE ?? DEFAULTS.telegramApiBase)
  const normalizedText = normalizeButtonText(text)
  if (normalizedText === '/start' || normalizedText === '/help') {
    ctx.waitUntil(clearSearchQuery(env.DELIVERIES, String(chatId)).then(() =>
      sendRandomButton(token, String(chatId), apiBase, normalizedText === '/start'),
    ).catch((error) => {
      console.error(`Failed to send menu to chat ${chatId}:`, errorMessage(error))
    }))
    return json({ ok: true })
  }

  if (normalizedText === '/cancel' || normalizedText === '取消') {
    ctx.waitUntil(clearSearchQuery(env.DELIVERIES, String(chatId)).then(() =>
      sendMessage(token, String(chatId), '已取消搜索。', apiBase),
    ))
    return json({ ok: true })
  }

  const waitingForSearch = await getSearchQueryState(env.DELIVERIES, String(chatId)).catch((error) => {
    console.error(`Failed to check search state for chat ${chatId}:`, errorMessage(error))
    ctx.waitUntil(sendMessage(token, String(chatId), '搜索状态暂时不可用，请稍后再试。', apiBase))
    return false
  })
  if (waitingForSearch && !text.startsWith('/')) {
    ctx.waitUntil(runImageCommand(env, token, String(chatId), {
      sorting: 'relevance',
      query: text.slice(0, MAX_SEARCH_QUERY_LENGTH),
    }))
    return json({ ok: true })
  }

  const command = parseImageCommand(text)
  if (command?.sorting === 'search-prompt') {
    ctx.waitUntil(beginSearchQuery(env.DELIVERIES, String(chatId)).then(() =>
      sendMessage(token, String(chatId), '发送关键字', apiBase),
    ).catch((error) => {
      console.error(`Failed to begin search for chat ${chatId}:`, errorMessage(error))
      return sendMessage(token, String(chatId), '搜索功能暂时不可用，请稍后再试。', apiBase)
    }))
    return json({ ok: true })
  }
  if (command) {
    ctx.waitUntil(runImageCommand(env, token, String(chatId), command).catch((error) => {
      console.error(`Failed to handle image command for chat ${chatId}:`, errorMessage(error))
      return sendMessage(token, String(chatId), '图片请求处理失败，请稍后再试。', apiBase)
    }))
  }

  return json({ ok: true })
}

function normalizeButtonText(text: string): string {
  return text
    .trim()
    .toLowerCase()
    .replace(/[︎️‍]/g, '')
    .replace(/\s+/g, ' ')
}

function parseImageCommand(text: string): ImageCommand | undefined {
  const normalizedText = normalizeButtonText(text)
  const buttonCommands: Record<string, string> = {
    '🎲 随机图片': '/random',
    '🔍 搜索图片': 'search-prompt',
    '🔍搜索图片': 'search-prompt',
    '🔥 热门': '/hot',
    '🌸 anime': '/anime',
    '👤 people': '/people',
    '🎨 general': '/general',
    '🖥 4k': '/4k',
    '📺 1080p': '/1080p',
    '📐 16:9': '/16x9',
    '🆕 最新': '/latest',
    '📖 使用说明': 'help',
    '❌ 隐藏菜单': 'remove-menu',
  }
  const mappedCommand = buttonCommands[normalizedText]
  const commandText = mappedCommand || normalizedText
  const [rawCommand, ...args] = commandText.split(/\s+/)
  const command = rawCommand.replace(/@[^\s]+$/, '')
  const query = args.join(' ').trim()

  if (command === 'search-prompt') {
    return { sorting: 'search-prompt' }
  }
  if (command === 'help') {
    return { sorting: 'help' }
  }
  if (command === 'remove-menu') {
    return { sorting: 'remove-menu' }
  }
  if (command === '/random' || command === '随机' || command === '随机图片' || command === '🎲') {
    return { sorting: 'random', random: true }
  }
  if (command === '/search' || command === '搜索' || command === '搜索图片') {
    return { sorting: 'relevance', query: query.slice(0, MAX_SEARCH_QUERY_LENGTH) }
  }
  if (command === '/anime' || command === 'anime' || command === '🌸') {
    return { sorting: 'random', categories: '010', random: true }
  }
  if (command === '/people' || command === 'people' || command === '👤') {
    return { sorting: 'random', categories: '001', random: true }
  }
  if (command === '/general' || command === 'general' || command === '🎨') {
    return { sorting: 'random', categories: '100', random: true }
  }
  if (command === '/4k' || command === '4k' || command === '🖥️') {
    return { sorting: 'random', atleast: '3840x2160', random: true }
  }
  if (command === '/1080p' || command === '1080p' || command === '📺') {
    return { sorting: 'random', atleast: '1920x1080', random: true }
  }
  if (command === '/16x9' || command === '16x9' || command === '📐') {
    return { sorting: 'random', ratio: '16x9', random: true }
  }
  if (command === '/hot' || command === '热门' || command === '🔥') {
    return { sorting: 'toplist', topRange: '1w' }
  }
  if (command === '/latest' || command === '最新' || command === '🆕') {
    return { sorting: 'date_added' }
  }
  return undefined
}

async function runImageCommand(
  env: Env,
  token: string,
  chatId: string,
  command: ImageCommand,
): Promise<void> {
  const config = getConfig(env)
  try {
    if (command.sorting === 'help') {
      await sendMessage(token, chatId, '用法：/search <关键词>，例如：/search cyberpunk', config.telegramApiBase)
      return
    }
    if (command.sorting === 'remove-menu') {
      await sendMessage(token, chatId, '已隐藏快捷按钮。发送 /start 可重新显示。', config.telegramApiBase, {
        remove_keyboard: true,
      })
      return
    }
    if (command.sorting === 'relevance' && !command.query) {
      await sendMessage(token, chatId, '用法：/search <关键词>，例如：/search cyberpunk', config.telegramApiBase)
      return
    }

    const cooldownKey = `command-cooldown:${encodeURIComponent(chatId)}`
    if (env.DELIVERIES) {
      await clearSearchQuery(env.DELIVERIES, chatId)
      const cooldown = await env.DELIVERIES.get(cooldownKey, 'json') as { expiresAt?: number } | null
      if (cooldown?.expiresAt && cooldown.expiresAt > Date.now()) {
        const seconds = Math.ceil((cooldown.expiresAt - Date.now()) / 1000)
        await sendMessage(token, chatId, `请求太频繁，请等待约 ${seconds} 秒后再试。`, config.telegramApiBase)
        return
      }
      await env.DELIVERIES.put(cooldownKey, JSON.stringify({ expiresAt: Date.now() + config.commandCooldownSeconds * 1000 }), {
        expirationTtl: Math.max(60, config.commandCooldownSeconds),
      })
    }

    const recentIds = command.random ? await getRecentRandomIds(env.DELIVERIES, chatId) : new Set<string>()
    let images = await fetchImages(config, env.WALLHAVEN_API_KEY, command.sorting, command.topRange, RANDOM_IMAGES_PER_COMMAND, {
      categories: command.categories,
      query: command.query,
      atleast: command.atleast,
      ratio: command.ratio,
      excludeIds: recentIds,
    })
    if (images.length < RANDOM_IMAGES_PER_COMMAND && recentIds.size > 0) {
      images = await fetchImages(config, env.WALLHAVEN_API_KEY, command.sorting, command.topRange, RANDOM_IMAGES_PER_COMMAND, {
        categories: command.categories,
        query: command.query,
        atleast: command.atleast,
        ratio: command.ratio,
      })
    }
    if (images.length === 0) {
      throw new Error('Wallhaven returned no usable images')
    }

    const downloadedImages = await Promise.all(images.map((image) => downloadImage(image, config)))
    await sendPhotoGroup(token, chatId, images, downloadedImages, config.telegramApiBase)
    if (command.random) {
      await saveRecentRandomIds(env.DELIVERIES, chatId, images.map((image) => image.id))
    }
    console.log(`Sent ${images.length} images for ${command.sorting} command to chat ${chatId}`)
  } catch (error) {
    console.error(`Failed to send ${command.sorting} images to chat ${chatId}:`, errorMessage(error))
    await sendMessage(token, chatId, commandErrorMessage(error), config.telegramApiBase).catch((sendError) => {
      console.error(`Failed to report image command error to chat ${chatId}:`, errorMessage(sendError))
    })
  }
}

function commandErrorMessage(error: unknown): string {
  if (error instanceof WallhavenApiError && error.status === 429) {
    return 'Wallhaven API 当前触发访问频率限制，请等待约 1 分钟后再试。'
  }
  if (error instanceof WallhavenApiError && error.status === 401) {
    return 'Wallhaven API Key 无效，或当前图片内容需要有效的 API Key。'
  }
  return 'Wallhaven API 暂时不可用，请稍后再试。'
}

async function getRecentRandomIds(deliveries: KVNamespace, chatId: string): Promise<Set<string>> {
  if (!deliveries) {
    return new Set()
  }
  const value = await deliveries.get(`recent-random:${encodeURIComponent(chatId)}`, 'json') as string[] | null
  return new Set(Array.isArray(value) ? value.filter((id): id is string => typeof id === 'string') : [])
}

async function saveRecentRandomIds(deliveries: KVNamespace, chatId: string, imageIds: string[]): Promise<void> {
  if (!deliveries) {
    return
  }
  const key = `recent-random:${encodeURIComponent(chatId)}`
  const previous = await getRecentRandomIds(deliveries, chatId)
  const ids = [...new Set([...previous, ...imageIds])].slice(-24)
  await deliveries.put(key, JSON.stringify(ids), { expirationTtl: RECENT_RANDOM_TTL_SECONDS })
}

async function getSearchQueryState(deliveries: KVNamespace | undefined, chatId: string): Promise<boolean> {
  if (!deliveries) {
    return false
  }
  try {
    return Boolean(await deliveries.get(`search-query:${encodeURIComponent(chatId)}`))
  } catch (error) {
    console.error(`Failed to read search state for chat ${chatId}:`, errorMessage(error))
    throw error
  }
}

async function beginSearchQuery(deliveries: KVNamespace | undefined, chatId: string): Promise<void> {
  if (!deliveries) {
    throw new Error('Missing DELIVERIES KV binding')
  }
  try {
    await deliveries.put(`search-query:${encodeURIComponent(chatId)}`, 'waiting', {
      expirationTtl: SEARCH_QUERY_TTL_SECONDS,
    })
  } catch (error) {
    console.error(`Failed to save search state for chat ${chatId}:`, errorMessage(error))
    throw new Error('Could not save search state')
  }
}

async function clearSearchQuery(deliveries: KVNamespace | undefined, chatId: string): Promise<void> {
  if (!deliveries) {
    return
  }
  try {
    await deliveries.delete(`search-query:${encodeURIComponent(chatId)}`)
  } catch (error) {
    console.error(`Failed to clear search state for chat ${chatId}:`, errorMessage(error))
  }
}

async function sendRandomButton(token: string, chatId: string, apiBase: string, isStart = false): Promise<void> {
  const message = isStart
    ? '欢迎使用 Wallhaven 图片 Bot！请选择下面的功能：\n\n🎲 随机图片：获取 3 张随机图片\n🔍 搜索图片：点击后发送关键词，直接搜索\n🔥 热门：获取最近一周热门图片\n🌸 Anime / 👤 People / 🎨 General：按分类获取图片\n🖥️ 4K / 📺 1080p / 📐 16:9：按分辨率或比例获取图片\n🆕 最新：获取最新上传图片\n\n也可以直接发送 /help 查看说明。'
    : '可用功能：\n\n🎲 随机图片：获取 3 张随机图片\n🔍 搜索图片：点击后发送关键词，直接搜索\n🔥 热门：最近一周热门图片\n🌸 Anime / 👤 People / 🎨 General：分类筛选\n🖥️ 4K / 📺 1080p / 📐 16:9：分辨率或比例筛选\n🆕 最新：最新上传图片\n\n搜索按钮使用方式：点击后发送关键词即可。也支持 /search <关键词>，例如 /search cyberpunk。\n\n命令：/random、/hot、/latest、/anime、/people、/general、/4k、/1080p、/16x9。'

  await sendMessage(token, chatId, message, apiBase, {
    keyboard: [
      ['🎲 随机图片', '🔍 搜索图片', '🔥 热门'],
      ['🌸 Anime', '👤 People', '🎨 General'],
      ['🖥️ 4K', '📺 1080p', '📐 16:9'],
      ['🆕 最新', '📖 使用说明', '❌ 隐藏菜单'],
    ],
    is_persistent: true,
    one_time_keyboard: false,
    resize_keyboard: true,
  })
}

async function sendMessage(
  token: string,
  chatId: string,
  text: string,
  apiBase: string,
  replyMarkup?: Record<string, unknown>,
): Promise<void> {
  const response = await fetch(`${apiBase}/bot${token}/sendMessage`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      chat_id: chatId,
      text,
      ...(replyMarkup ? { reply_markup: replyMarkup } : {}),
    }),
  })
  const payload = (await response.json().catch(() => ({}))) as TelegramResponse

  if (!response.ok || payload.ok !== true) {
    throw new Error(`Telegram HTTP ${response.status}: ${payload.description || 'sendMessage failed'}`)
  }
}

export async function runScheduled(env: Env): Promise<void> {
  const token = env.WALLHAVEN_TELEGRAM_BOT_TOKEN?.trim()
  const chatIds = parseChatIds(env.WALLHAVEN_TELEGRAM_CHAT_IDS)

  if (!token || chatIds.length === 0) {
    console.error('Missing WALLHAVEN_TELEGRAM_BOT_TOKEN or WALLHAVEN_TELEGRAM_CHAT_IDS')
    return
  }

  if (!env.DELIVERIES) {
    console.error('Missing DELIVERIES KV binding')
    return
  }

  const config = getConfig(env)
  const feeds = [
    { name: 'latest', sorting: 'date_added', topRange: undefined, limit: config.imagesPerRun },
    { name: 'toplist-1M', sorting: 'toplist', topRange: '1M', limit: config.topImagesPerRun },
  ]
  const downloadedImages = new Map<string, { bytes: Uint8Array; contentType: string }>()

  for (const feed of feeds) {
    let images: WallhavenImage[]

    try {
      images = await fetchImages(config, env.WALLHAVEN_API_KEY, feed.sorting, feed.topRange, feed.limit)
    } catch (error) {
      console.error(`${feed.name} Wallhaven request failed:`, errorMessage(error))
      continue
    }

    if (images.length === 0) {
      console.log(`${feed.name} Wallhaven returned no usable images`)
      continue
    }

    await deliverImages(token, chatIds, images, feed.name, config, env.DELIVERIES, downloadedImages)
  }
}

function getConfig(env: Env) {
  return {
    telegramApiBase: normalizeTelegramApiBase(env.WALLHAVEN_TELEGRAM_API_BASE ?? DEFAULTS.telegramApiBase),
    wallhavenApiUrl: env.WALLHAVEN_API_URL?.trim() || DEFAULTS.wallhavenApiUrl,
    categories: env.WALLHAVEN_CATEGORIES?.trim() || DEFAULTS.categories,
    purity: env.WALLHAVEN_PURITY?.trim() || DEFAULTS.purity,
    imagesPerRun: boundedInteger(env.WALLHAVEN_IMAGES_PER_RUN, DEFAULTS.imagesPerRun, 1, 10),
    topImagesPerRun: boundedInteger(env.WALLHAVEN_TOP_IMAGES_PER_RUN, DEFAULTS.imagesPerRun, 1, 10),
    imageSource: env.WALLHAVEN_IMAGE_SOURCE?.trim().toLowerCase() || DEFAULTS.imageSource,
    maxImageBytes: boundedInteger(env.WALLHAVEN_MAX_IMAGE_BYTES, DEFAULTS.maxImageBytes, 1, 50_000_000),
    sentTtlSeconds: boundedInteger(env.WALLHAVEN_SENT_TTL_SECONDS, DEFAULTS.sentTtlSeconds, 60, 2_147_483_647),
    commandCooldownSeconds: boundedInteger(env.WALLHAVEN_COMMAND_COOLDOWN_SECONDS, DEFAULTS.commandCooldownSeconds, 10, 3_600),
  }
}

export function parseChatIds(value: string | undefined): string[] {
  return [...new Set((value ?? '').split(',').map((id) => id.trim()).filter(Boolean))]
}

export function buildWallhavenUrl(
  baseUrl: string,
  categories: string,
  purity: string,
  sorting = 'date_added',
  topRange?: string,
  options: ImageRequestOptions = {},
): string {
  const url = new URL(baseUrl)
  url.searchParams.set('sorting', sorting)
  url.searchParams.set('order', 'desc')
  url.searchParams.set('page', '1')
  url.searchParams.set('categories', options.categories || categories)
  url.searchParams.set('purity', purity)
  if (topRange) {
    url.searchParams.set('topRange', topRange)
  }
  if (options.query) {
    url.searchParams.set('q', options.query)
  }
  if (options.atleast) {
    url.searchParams.set('atleast', options.atleast)
  }
  if (options.ratio) {
    url.searchParams.set('ratios', options.ratio)
  }
  return url.toString()
}

async function fetchImages(
  config: ReturnType<typeof getConfig>,
  apiKey: string | undefined,
  sorting: string,
  topRange: string | undefined,
  limit: number,
  options: ImageRequestOptions = {},
): Promise<WallhavenImage[]> {
  const headers = new Headers({ Accept: 'application/json' })
  if (apiKey?.trim()) {
    headers.set('X-API-Key', apiKey.trim())
  }

  const response = await fetch(buildWallhavenUrl(
    config.wallhavenApiUrl,
    config.categories,
    config.purity,
    sorting,
    topRange,
    options,
  ), {
    headers,
  })

  if (!response.ok) {
    throw new WallhavenApiError(response.status)
  }

  const payload = (await response.json()) as WallhavenSearchResponse
  if (!Array.isArray(payload.data)) {
    throw new Error('Wallhaven response has no data array')
  }

  const seen = new Set<string>()
  const usable: WallhavenImage[] = []
  for (const image of payload.data) {
    if (!image || typeof image.id !== 'string' || seen.has(image.id) || options.excludeIds?.has(image.id)) {
      continue
    }
    if (image.path || image.thumbs?.large || image.thumbs?.original || image.thumbs?.small) {
      seen.add(image.id)
      usable.push(image)
    }
    if (usable.length >= limit) {
      break
    }
  }

  return Promise.all(usable.map(async (image) => {
    try {
      return await fetchImageDetails(image, config.wallhavenApiUrl, apiKey)
    } catch (error) {
      console.warn(`Could not load details for Wallhaven image ${image.id}:`, errorMessage(error))
      return image
    }
  }))
}

async function deliverImages(
  token: string,
  chatIds: string[],
  images: WallhavenImage[],
  feedName: string,
  config: ReturnType<typeof getConfig>,
  deliveries: KVNamespace,
  downloadedImages: Map<string, { bytes: Uint8Array; contentType: string }>,
): Promise<void> {
  for (const chatId of chatIds) {
    for (const image of images) {
      const key = deliveryKey(feedName, chatId, image.id)
      if (await deliveries.get(key)) {
        continue
      }

      try {
        let downloaded = downloadedImages.get(image.id)
        if (!downloaded) {
          downloaded = await downloadImage(image, config)
          downloadedImages.set(image.id, downloaded)
        }

        await sendPhoto(token, chatId, image, downloaded.bytes, downloaded.contentType, config.telegramApiBase)
        await deliveries.put(key, 'sent', { expirationTtl: config.sentTtlSeconds })
        console.log(`Sent ${feedName} Wallhaven image ${image.id} to chat ${chatId}`)
      } catch (error) {
        console.error(`Failed to send ${feedName} image ${image.id} to chat ${chatId}:`, errorMessage(error))
      }
    }
  }
}

async function fetchImageDetails(
  image: WallhavenImage,
  searchApiUrl: string,
  apiKey: string | undefined,
): Promise<WallhavenImage> {
  const headers = new Headers({ Accept: 'application/json' })
  if (apiKey?.trim()) {
    headers.set('X-API-Key', apiKey.trim())
  }

  const searchUrl = new URL(searchApiUrl)
  const detailUrl = `${searchUrl.origin}/api/v1/w/${encodeURIComponent(image.id)}`
  const response = await fetch(detailUrl, { headers })
  if (!response.ok) {
    throw new WallhavenApiError(response.status)
  }

  const payload = (await response.json()) as WallhavenDetailResponse
  return payload.data ? { ...image, ...payload.data } : image
}

async function downloadImage(
  image: WallhavenImage,
  config: ReturnType<typeof getConfig>,
): Promise<{ bytes: Uint8Array; contentType: string }> {
  const imageUrls = imageUrlsForDownload(image, config.imageSource)
  if (imageUrls.length === 0) {
    throw new Error(`Image ${image.id} has no usable URL`)
  }

  let lastError: unknown
  for (const imageUrl of imageUrls) {
    try {
      const response = await fetch(imageUrl)
      if (!response.ok) {
        throw new Error(`Image ${image.id} HTTP ${response.status}`)
      }

      const contentLength = Number(response.headers.get('content-length') ?? 0)
      if (contentLength > config.maxImageBytes) {
        throw new Error(`Image ${image.id} exceeds size limit`)
      }

      const bytes = new Uint8Array(await response.arrayBuffer())
      if (bytes.byteLength === 0 || bytes.byteLength > config.maxImageBytes) {
        throw new Error(`Image ${image.id} has invalid size`)
      }

      if (bytes.byteLength > TELEGRAM_MAX_PHOTO_BYTES) {
        throw new Error(`Image ${image.id} exceeds Telegram photo size limit`)
      }

      const responseType = response.headers.get('content-type')?.split(';', 1)[0]?.trim()
      const contentType = responseType?.startsWith('image/') ? responseType : image.file_type || 'image/jpeg'
      if (!contentType.startsWith('image/')) {
        throw new Error(`Image ${image.id} has unsupported content type`)
      }

      return { bytes, contentType }
    } catch (error) {
      lastError = error
      console.warn(`Could not download Wallhaven image ${image.id} from ${imageUrl}:`, errorMessage(error))
    }
  }

  throw lastError instanceof Error ? lastError : new Error(`Image ${image.id} could not be downloaded`)
}

function imageUrlsForDownload(image: WallhavenImage, source: string): string[] {
  const preferred = selectImageUrl(image, source)
  return [...new Set([
    preferred,
    image.thumbs?.large,
    image.thumbs?.original,
    image.thumbs?.small,
  ].filter((url): url is string => Boolean(url)))]
}

function selectImageUrl(image: WallhavenImage, source: string): string | undefined {
  if (source === 'path') {
    return image.path || image.thumbs?.large || image.thumbs?.original
  }
  if (source === 'original') {
    return image.thumbs?.original || image.path || image.thumbs?.large
  }
  return image.thumbs?.large || image.path || image.thumbs?.original
}

async function sendPhoto(
  token: string,
  chatId: string,
  image: WallhavenImage,
  bytes: Uint8Array,
  contentType: string,
  apiBase: string,
): Promise<void> {
  const form = new FormData()
  form.set('chat_id', chatId)
  const blobBytes = new ArrayBuffer(bytes.byteLength)
  new Uint8Array(blobBytes).set(bytes)
  form.set('photo', new Blob([blobBytes], { type: contentType }), fileName(image, contentType))
  form.set('caption', caption(image))

  const response = await fetch(`${apiBase}/bot${token}/sendPhoto`, {
    method: 'POST',
    body: form,
  })
  const payload = (await response.json().catch(() => ({}))) as TelegramResponse

  if (!response.ok || payload.ok !== true) {
    throw new Error(`Telegram HTTP ${response.status}: ${payload.description || 'sendPhoto failed'}`)
  }
}

async function sendPhotoGroup(
  token: string,
  chatId: string,
  images: WallhavenImage[],
  downloadedImages: { bytes: Uint8Array; contentType: string }[],
  apiBase: string,
): Promise<void> {
  const form = new FormData()
  form.set('chat_id', chatId)
  const media = images.map((image, index) => ({
    type: 'photo',
    media: `attach://photo${index}`,
    caption: caption(image),
  }))
  form.set('media', JSON.stringify(media))

  downloadedImages.forEach((downloaded, index) => {
    const blobBytes = new ArrayBuffer(downloaded.bytes.byteLength)
    new Uint8Array(blobBytes).set(downloaded.bytes)
    form.set(`photo${index}`, new Blob([blobBytes], { type: downloaded.contentType }), fileName(images[index], downloaded.contentType))
  })

  const response = await fetch(`${apiBase}/bot${token}/sendMediaGroup`, {
    method: 'POST',
    body: form,
  })
  const payload = (await response.json().catch(() => ({}))) as TelegramResponse

  if (!response.ok || payload.ok !== true) {
    throw new Error(`Telegram HTTP ${response.status}: ${payload.description || 'sendMediaGroup failed'}`)
  }
}

export function normalizeTelegramApiBase(value: string): string {
  return value.trim().replace(/\/+$/, '').replace(/\/bot$/, '')
}

export function caption(image: WallhavenImage): string {
  const tags = (image.tags ?? [])
    .map((tag) => tag.name?.trim() || tag.alias?.trim())
    .filter((tag): tag is string => Boolean(tag))
    .slice(0, 30)
    .join(', ')

  const details = [
    `Wallhaven ${image.id}`,
    image.category ? `类别: ${categoryLabel(image.category)}` : '',
    image.purity ? `内容级别: ${purityLabel(image.purity)}` : '',
    image.created_at ? `上传时间: ${image.created_at}` : '',
    image.resolution || image.dimension_x && image.dimension_y
      ? `分辨率: ${image.resolution || `${image.dimension_x}x${image.dimension_y}`}`
      : '',
    image.file_type ? `格式: ${image.file_type}` : '',
    tags ? `标签: ${tags}` : '标签: 无',
    image.url ? `详情: ${image.url}` : '',
  ].filter(Boolean)

  return details.join('\n').slice(0, 1024)
}

function categoryLabel(category: string): string {
  const labels: Record<string, string> = {
    general: 'General',
    anime: 'Anime',
    people: 'People',
  }
  return labels[category] || category
}

function purityLabel(purity: string): string {
  const labels: Record<string, string> = {
    sfw: 'SFW',
    sketchy: 'Sketchy',
    nsfw: 'NSFW',
  }
  return labels[purity] || purity
}

function fileName(image: WallhavenImage, contentType: string): string {
  const extension = contentType.split('/')[1]?.split('+')[0] || 'jpg'
  return `${image.id}.${extension}`
}

function deliveryKey(feedName: string, chatId: string, imageId: string): string {
  return `sent:${encodeURIComponent(feedName)}:${encodeURIComponent(chatId)}:${encodeURIComponent(imageId)}`
}

function boundedInteger(value: string | undefined, fallback: number, min: number, max: number): number {
  const parsed = Number.parseInt(value ?? '', 10)
  return Number.isFinite(parsed) ? Math.min(Math.max(parsed, min), max) : fallback
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8' },
  })
}
