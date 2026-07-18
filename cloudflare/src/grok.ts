import type { Context } from 'hono';
import type { Env } from './types';

/**
 * Proxies POST /v1/chat/completions to xAI, injecting the server-held API key.
 * The request/response bodies are passed through untouched, so the OpenAI-
 * compatible schema (and SSE streaming when `stream: true`) just works.
 */
export async function proxyChat(c: Context<{ Bindings: Env }>): Promise<Response> {
  const apiKey = c.env.GROK_API_KEY;
  if (!apiKey) {
    return c.json({ error: 'server_misconfigured', message: 'GROK_API_KEY is not set' }, 500);
  }

  const base = (c.env.GROK_BASE_URL || 'https://api.x.ai').replace(/\/+$/, '');
  const body = await c.req.text();

  let upstream: Response;
  try {
    upstream = await fetch(`${base}/v1/chat/completions`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
        Accept: c.req.header('Accept') ?? 'application/json',
      },
      body,
    });
  } catch (err) {
    return c.json({ error: 'upstream_unreachable', message: String(err) }, 502);
  }

  // Pass status + body straight through (preserves streaming).
  return new Response(upstream.body, {
    status: upstream.status,
    headers: {
      'Content-Type': upstream.headers.get('Content-Type') ?? 'application/json',
    },
  });
}
