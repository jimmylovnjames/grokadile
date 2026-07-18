import type { MiddlewareHandler } from 'hono';
import type { Env } from './types';
import { bearerToken, timingSafeEqual } from './util';

/**
 * Requires `Authorization: Bearer <APP_AUTH_TOKEN>` on protected routes. If
 * APP_AUTH_TOKEN is not configured the worker runs open (handy for first-run
 * local dev) — set it before exposing the worker publicly.
 */
export const bearerAuth: MiddlewareHandler<{ Bindings: Env }> = async (c, next) => {
  const expected = c.env.APP_AUTH_TOKEN;
  if (expected) {
    const token = bearerToken(c.req.header('Authorization'));
    if (!token || !timingSafeEqual(token, expected)) {
      return c.json({ error: 'unauthorized' }, 401);
    }
  }
  await next();
};
