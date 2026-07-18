/** D1 persistence for agent tasks + traces, shared by the DO and REST handlers. */
import type { Env } from './types';
import type { AgentTask, Constraints, Target, TaskStatus } from './contract';

interface TaskRow {
  id: string;
  agent_id: string;
  goal: string;
  platform: string;
  device_id: string | null;
  constraints: string;
  skills: string;
  status: string;
  summary: string | null;
  created_at: number;
  updated_at: number;
}

function rowToTask(r: TaskRow): AgentTask {
  return {
    id: r.id,
    agentId: r.agent_id,
    goal: r.goal,
    target: { platform: r.platform as Target['platform'], deviceId: r.device_id ?? undefined },
    constraints: JSON.parse(r.constraints) as Constraints,
    skills: JSON.parse(r.skills) as string[],
    status: r.status as TaskStatus,
    summary: r.summary ?? undefined,
    createdAt: r.created_at,
    updatedAt: r.updated_at,
  };
}

export async function insertTask(env: Env, task: AgentTask): Promise<void> {
  await env.DB.prepare(
    `INSERT INTO agent_tasks
       (id, agent_id, goal, platform, device_id, constraints, skills, status, summary, created_at, updated_at)
     VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)`,
  )
    .bind(
      task.id,
      task.agentId,
      task.goal,
      task.target.platform,
      task.target.deviceId ?? null,
      JSON.stringify(task.constraints),
      JSON.stringify(task.skills),
      task.status,
      task.summary ?? null,
      task.createdAt,
      task.updatedAt,
    )
    .run();
}

export async function getTask(env: Env, id: string): Promise<AgentTask | null> {
  const row = await env.DB.prepare(`SELECT * FROM agent_tasks WHERE id = ?1`).bind(id).first<TaskRow>();
  return row ? rowToTask(row) : null;
}

export async function updateTaskStatus(
  env: Env,
  id: string,
  status: TaskStatus,
  summary?: string,
): Promise<void> {
  await env.DB.prepare(
    `UPDATE agent_tasks SET status = ?2, summary = COALESCE(?3, summary), updated_at = ?4 WHERE id = ?1`,
  )
    .bind(id, status, summary ?? null, Date.now())
    .run();
}

export async function nextQueuedTaskForAgent(env: Env, agentId: string): Promise<AgentTask | null> {
  const row = await env.DB.prepare(
    `SELECT * FROM agent_tasks WHERE agent_id = ?1 AND status = 'queued' ORDER BY created_at ASC LIMIT 1`,
  )
    .bind(agentId)
    .first<TaskRow>();
  return row ? rowToTask(row) : null;
}

export async function appendTrace(
  env: Env,
  taskId: string,
  agentId: string,
  step: number,
  type: string,
  payload: unknown,
): Promise<void> {
  await env.DB.prepare(
    `INSERT INTO agent_traces (task_id, agent_id, step, type, payload, created_at)
     VALUES (?1,?2,?3,?4,?5,?6)`,
  )
    .bind(taskId, agentId, step, type, JSON.stringify(payload), Date.now())
    .run();
}
