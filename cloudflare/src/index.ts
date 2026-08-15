import { Hono } from 'hono';
import type { Env } from './types';
import { bearerAuth } from './auth';
import { proxyChat } from './grok';
import { pullTasks, enqueueTask } from './tasks';
import { postReport } from './reports';
import { deviceHeartbeat, listDevices } from './devices';

const VERSION = '0.3.0';

const app = new Hono<{ Bindings: Env }>();

app.get('/health', (c) => c.json({ status: 'ok', version: VERSION, time: Date.now() }));

app.use('/v1/*', bearerAuth);
app.use('/agents/*', bearerAuth);
app.use('/devices/*', bearerAuth);
app.use('/devices', bearerAuth);

app.post('/v1/chat/completions', proxyChat);

app.post('/devices/heartbeat', deviceHeartbeat);
app.get('/devices', listDevices);

app.get('/agents/:agentId/tasks', pullTasks);
app.post('/agents/:agentId/tasks', enqueueTask);
app.post('/agents/:agentId/report', postReport);

app.notFound((c) => c.json({ error: 'not_found' }, 404));
app.onError((err, c) => {
  console.error('worker error:', err);
  return c.json({ error: 'internal', message: String(err) }, 500);
});

export default app;
