interface Env {
  DELIVERIES: KVNamespace
  WALLHAVEN_TELEGRAM_BOT_TOKEN?: string
  WALLHAVEN_TELEGRAM_CHAT_IDS?: string
  WALLHAVEN_TELEGRAM_API_BASE?: string
  WALLHAVEN_API_URL?: string
  WALLHAVEN_API_KEY?: string
  WALLHAVEN_CATEGORIES?: string
  WALLHAVEN_PURITY?: string
  WALLHAVEN_IMAGES_PER_RUN?: string
  WALLHAVEN_TOP_IMAGES_PER_RUN?: string
  WALLHAVEN_IMAGE_SOURCE?: string
  WALLHAVEN_MAX_IMAGE_BYTES?: string
  WALLHAVEN_SENT_TTL_SECONDS?: string
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
  }
}

interface WallhavenDetailResponse {
  data?: WallhavenImage
}

interface TelegramResponse {
  ok?: boolean
  description?: string
  result?: unknown
}

const DEFAULTS = {
  telegramApiBase: 'https://api.telegram.org',
  wallhavenApiUrl: 'https://wallhaven.cc/api/v1/search',
  categories: '000',
  purity: '000',
  imagesPerRun: 3,
  imageSource: 'path',
  maxImageBytes: 10_000_000,
  sentTtlSeconds: 2_592_000,
} as const

export default {
  async fetch(): Promise<Response> {
    return json({
      service: 'wallhaven-image-bot',
      status: 'ok',
      schedule: '*/30 * * * *',
    })
  },

  async scheduled(
    _controller: ScheduledController,
    env: Env,
    ctx: ExecutionContext,
  ): Promise<void> {
    ctx.waitUntil(runScheduled(env))
  },
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
): string {
  const url = new URL(baseUrl)
  url.searchParams.set('sorting', sorting)
  url.searchParams.set('order', 'desc')
  url.searchParams.set('page', '1')
  url.searchParams.set('categories', categories)
  url.searchParams.set('purity', purity)
  if (sorting === 'toplist' && topRange) {
    url.searchParams.set('topRange', topRange)
  }
  return url.toString()
}

async function fetchImages(
  config: ReturnType<typeof getConfig>,
  apiKey: string | undefined,
  sorting: string,
  topRange: string | undefined,
  limit: number,
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
  ), {
    headers,
  })

  if (!response.ok) {
    throw new Error(`Wallhaven HTTP ${response.status}`)
  }

  const payload = (await response.json()) as WallhavenSearchResponse
  if (!Array.isArray(payload.data)) {
    throw new Error('Wallhaven response has no data array')
  }

  const seen = new Set<string>()
  const usable: WallhavenImage[] = []
  for (const image of payload.data) {
    if (!image || typeof image.id !== 'string' || seen.has(image.id)) {
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
    throw new Error(`Wallhaven detail HTTP ${response.status}`)
  }

  const payload = (await response.json()) as WallhavenDetailResponse
  return payload.data ? { ...image, ...payload.data } : image
}

async function downloadImage(
  image: WallhavenImage,
  config: ReturnType<typeof getConfig>,
): Promise<{ bytes: Uint8Array; contentType: string }> {
  const imageUrl = selectImageUrl(image, config.imageSource)
  if (!imageUrl) {
    throw new Error(`Image ${image.id} has no usable URL`)
  }

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

  const responseType = response.headers.get('content-type')?.split(';', 1)[0]?.trim()
  const contentType = responseType?.startsWith('image/') ? responseType : image.file_type || 'image/jpeg'
  if (!contentType.startsWith('image/')) {
    throw new Error(`Image ${image.id} has unsupported content type`)
  }

  return { bytes, contentType }
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
