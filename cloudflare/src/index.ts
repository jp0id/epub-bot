import {Hono} from 'hono'

type Bindings = {
    BUCKET: R2Bucket
}

const app = new Hono<{ Bindings: Bindings }>()

app.use('*', async (c, next) => {
    await next()
    c.header('Access-Control-Allow-Origin', '*')
})

app.get('/*', async (c) => {
    const key = c.req.path.slice(1)

    const object = await c.env.BUCKET.get(key)

    if (!object) {
        return c.text('404 Not Found', 404)
    }

    const headers = new Headers()
    object.writeHttpMetadata(headers)
    headers.set('etag', object.httpEtag)

    headers.set('Cache-Control', 'public, max-age=31536000')

    return new Response(object.body, {
        headers,
    })
})

export default app