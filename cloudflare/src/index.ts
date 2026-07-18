import { Hono } from 'hono';
import type { Env } from './types';
import { bearerAuth } from './auth';
import { proxyChat } from './grok';
import { pullTasks, enqueueTask } from './tasks';
import { postReport } from './reports';
import { getMemory, putMemory } from './memory';

const VERSION = '0.2.0';

const app = new Hono<{ Bindings: Env }>();

// Public liveness check (matches the app's CloudflareApi.health()).
app.get('/health', (c) => c.json({ status: 'ok', version: VERSION, time: Date.now() }));

// Everything else requires the shared app token (when configured).
app.use('/v1/*', bearerAuth);
app.use('/agents/*', bearerAuth);

// Grok chat proxy (key stays server-side).
app.post('/v1/chat/completions', proxyChat);

// Agent control plane.
app.get('/agents/:agentId/tasks', pullTasks);
app.post('/agents/:agentId/tasks', enqueueTask);
app.post('/agents/:agentId/report', postReport);

// Shared memory: the same "person" across every device and surface.
app.get('/agents/:agentId/memory', getMemory);
app.put('/agents/:agentId/memory', putMemory);

app.notFound((c) => c.json({ error: 'not_found' }, 404));
app.onError((err, c) => {
  console.error('worker error:', err);
  return c.json({ error: 'internal', message: String(err) }, 500);
});

export default app;
