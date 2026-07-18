import { Hono } from 'hono';
import type { Env } from './types';
import { bearerAuth } from './auth';
import { proxyChat } from './grok';
import { pullTasks, enqueueTask } from './tasks';
import { postReport } from './reports';
import {
  connectAgent,
  controlTask,
  createTask,
  getScreenshot,
  getTaskStatus,
  putScreenshot,
} from './agentTasks';

const VERSION = '0.2.0';

const app = new Hono<{ Bindings: Env }>();

// Public liveness check (matches the app's CloudflareApi.health()).
app.get('/health', (c) => c.json({ status: 'ok', version: VERSION, time: Date.now() }));

// Everything else requires the shared app token (when configured).
app.use('/v1/*', bearerAuth);
app.use('/agents/*', bearerAuth);

// Grok chat proxy (key stays server-side).
app.post('/v1/chat/completions', proxyChat);

// Screen-agent orchestration: tasks, live WebSocket control, screenshots.
app.post('/v1/agent/tasks', createTask);
app.get('/v1/agent/tasks/:id', getTaskStatus);
app.post('/v1/agent/tasks/:id/control', controlTask);
app.get('/v1/agent/connect', connectAgent);
app.put('/v1/agent/screenshots/:id', putScreenshot);
app.get('/v1/agent/screenshots/:id', getScreenshot);

// Lightweight per-agent control plane used by the on-device app.
app.get('/agents/:agentId/tasks', pullTasks);
app.post('/agents/:agentId/tasks', enqueueTask);
app.post('/agents/:agentId/report', postReport);

app.notFound((c) => c.json({ error: 'not_found' }, 404));
app.onError((err, c) => {
  console.error('worker error:', err);
  return c.json({ error: 'internal', message: String(err) }, 500);
});

export default app;
export { AgentSession } from './agentSession';
