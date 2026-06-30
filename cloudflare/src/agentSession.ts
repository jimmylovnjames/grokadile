import type { Env } from './types';
import { ClientMessageSchema, type ClientMessage, type ServerMessage } from './contract';
import { appendTrace, getTask, nextQueuedTaskForAgent, updateTaskStatus } from './store';

/**
 * One Durable Object per agentId — the live coordination point between the
 * Worker and a connected runner. Holds the WebSocket (hibernatable), assigns
 * queued tasks, fans control messages out to the agent, and persists every
 * inbound telemetry message as a trace. REST handlers reach it via stub.fetch
 * with internal paths (/assign, /control); the agent connects via /connect.
 */
export class AgentSession {
  constructor(
    private readonly state: DurableObjectState,
    private readonly env: Env,
  ) {}

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const action = url.pathname.split('/').pop();

    if (action === 'connect') {
      if (request.headers.get('Upgrade') !== 'websocket') {
        return new Response('expected websocket upgrade', { status: 426 });
      }
      const agentId = url.searchParams.get('agentId') ?? 'unknown';
      const pair = new WebSocketPair();
      const client = pair[0];
      const server = pair[1];
      // Tag the socket with the agentId so we can attribute messages after hibernation.
      this.state.acceptWebSocket(server, [agentId]);
      // Hand over any already-queued work (floating — DO stays alive while connected).
      this.assignNext(agentId).catch(() => undefined);
      return new Response(null, { status: 101, webSocket: client });
    }

    if (action === 'assign') {
      const { taskId } = (await request.json()) as { taskId: string };
      await this.assignTask(taskId);
      return Response.json({ ok: true });
    }

    if (action === 'control') {
      const message = (await request.json()) as ServerMessage;
      this.broadcast(message);
      return Response.json({ ok: true });
    }

    return new Response('not found', { status: 404 });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    let raw: unknown;
    try {
      const text = typeof message === 'string' ? message : new TextDecoder().decode(message);
      raw = JSON.parse(text);
    } catch {
      ws.send(JSON.stringify({ type: 'error', error: 'invalid json' } satisfies ServerMessage));
      return;
    }
    const parsed = ClientMessageSchema.safeParse(raw);
    if (!parsed.success) {
      ws.send(JSON.stringify({ type: 'error', error: 'invalid message' } satisfies ServerMessage));
      return;
    }
    const agentId = this.state.getTags(ws)[0] ?? '';
    await this.handle(agentId, parsed.data);
  }

  async webSocketClose(ws: WebSocket): Promise<void> {
    try {
      ws.close();
    } catch {
      // already closing
    }
  }

  private async handle(agentId: string, msg: ClientMessage): Promise<void> {
    switch (msg.type) {
      case 'status':
        await appendTrace(this.env, msg.taskId, agentId, msg.step, 'status', msg);
        break;
      case 'observation':
        await appendTrace(this.env, msg.taskId, agentId, msg.step, 'observation', msg);
        break;
      case 'action':
        await appendTrace(this.env, msg.taskId, agentId, msg.step, 'action', msg);
        break;
      case 'escalation':
        await appendTrace(this.env, msg.taskId, agentId, 0, 'escalation', msg);
        await updateTaskStatus(this.env, msg.taskId, 'escalated');
        break;
      case 'result':
        await appendTrace(this.env, msg.taskId, agentId, msg.steps, 'result', msg);
        await updateTaskStatus(this.env, msg.taskId, msg.status, msg.summary);
        break;
      case 'hello':
      case 'heartbeat':
        break;
    }
  }

  private async assignNext(agentId: string): Promise<void> {
    const task = await nextQueuedTaskForAgent(this.env, agentId);
    if (task) await this.assignTask(task.id);
  }

  private async assignTask(taskId: string): Promise<void> {
    const task = await getTask(this.env, taskId);
    if (!task) return;
    await updateTaskStatus(this.env, taskId, 'assigned');
    this.broadcast({ type: 'assign', task: { ...task, status: 'assigned' } });
  }

  private broadcast(message: ServerMessage): void {
    const text = JSON.stringify(message);
    for (const ws of this.state.getWebSockets()) {
      try {
        ws.send(text);
      } catch {
        // socket gone; ignore
      }
    }
  }
}
