import type { Context } from 'hono';
import type { Env } from './types';
import { ConstraintsSchema, CreateTaskSchema, type AgentTask } from './contract';
import { getTask, insertTask, updateTaskStatus } from './store';

type Ctx = Context<{ Bindings: Env }>;

function sessionStub(env: Env, agentId: string) {
  return env.AGENT_SESSION.get(env.AGENT_SESSION.idFromName(agentId));
}

/** POST /v1/agent/tasks — validate, persist (queued), and nudge the agent's session. */
export async function createTask(c: Ctx): Promise<Response> {
  const parsed = CreateTaskSchema.safeParse(await c.req.json().catch(() => null));
  if (!parsed.success) {
    return c.json({ error: 'invalid_task', issues: parsed.error.issues }, 400);
  }
  const body = parsed.data;
  const now = Date.now();
  const task: AgentTask = {
    id: crypto.randomUUID(),
    agentId: body.agentId,
    goal: body.goal,
    target: body.target,
    constraints: ConstraintsSchema.parse(body.constraints ?? {}),
    skills: body.skills,
    status: 'queued',
    createdAt: now,
    updatedAt: now,
  };
  await insertTask(c.env, task);

  // If the agent is connected its DO assigns immediately; otherwise it picks the
  // task up on its next /connect. Either way task creation succeeds.
  try {
    await sessionStub(c.env, task.agentId).fetch('https://session/assign', {
      method: 'POST',
      body: JSON.stringify({ taskId: task.id }),
    });
  } catch {
    // agent offline — leave it queued
  }
  return c.json({ taskId: task.id, status: task.status }, 201);
}

/** GET /v1/agent/tasks/:id — current task record. */
export async function getTaskStatus(c: Ctx): Promise<Response> {
  const id = c.req.param('id')!;
  const task = await getTask(c.env, id);
  return task ? c.json(task) : c.json({ error: 'not_found' }, 404);
}

/** POST /v1/agent/tasks/:id/control — pause/resume/cancel/answer, forwarded over WS. */
export async function controlTask(c: Ctx): Promise<Response> {
  const id = c.req.param('id')!;
  const body = (await c.req.json().catch(() => ({}))) as { command?: string; payload?: unknown };
  const task = await getTask(c.env, id);
  if (!task) return c.json({ error: 'not_found' }, 404);
  const command = body.command ?? 'cancel';

  await sessionStub(c.env, task.agentId).fetch('https://session/control', {
    method: 'POST',
    body: JSON.stringify({ type: 'control', taskId: id, command, payload: body.payload }),
  });
  if (command === 'cancel') await updateTaskStatus(c.env, id, 'cancelled');
  return c.json({ ok: true });
}

/** GET /v1/agent/connect — upgrades to WebSocket, routed to the agent's DO. */
export async function connectAgent(c: Ctx): Promise<Response> {
  const agentId = c.req.query('agentId');
  if (!agentId) return c.json({ error: 'agentId query param required' }, 400);
  return sessionStub(c.env, agentId).fetch(c.req.raw);
}

/** PUT /v1/agent/screenshots/:id — store a failure screenshot in R2. */
export async function putScreenshot(c: Ctx): Promise<Response> {
  const id = c.req.param('id')!;
  const body = c.req.raw.body;
  if (!body) return c.json({ error: 'empty_body' }, 400);
  await c.env.BUCKET.put(`screenshots/${id}`, body, {
    httpMetadata: { contentType: c.req.header('Content-Type') ?? 'image/png' },
  });
  return c.json({ screenshotId: id }, 201);
}

/** GET /v1/agent/screenshots/:id — retrieve a stored screenshot. */
export async function getScreenshot(c: Ctx): Promise<Response> {
  const id = c.req.param('id')!;
  const object = await c.env.BUCKET.get(`screenshots/${id}`);
  if (!object) return c.json({ error: 'not_found' }, 404);
  return new Response(object.body, {
    headers: { 'Content-Type': object.httpMetadata?.contentType ?? 'image/png' },
  });
}
