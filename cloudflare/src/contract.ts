/**
 * The agent <-> Worker API contract. Single source of truth for task, action,
 * and WebSocket message shapes, shared by the Worker and the host-side runner.
 * Zod gives us runtime validation (untrusted WS/REST input) plus inferred TS
 * types, so the contract can't silently drift between the two sides.
 */
import { z } from 'zod';

// --- platform / task -------------------------------------------------------

export const PlatformSchema = z.enum(['android', 'desktop']);
export type Platform = z.infer<typeof PlatformSchema>;

export const TaskStatusSchema = z.enum([
  'queued',
  'assigned',
  'running',
  'succeeded',
  'failed',
  'escalated',
  'cancelled',
]);
export type TaskStatus = z.infer<typeof TaskStatusSchema>;

export const ConstraintsSchema = z.object({
  maxSteps: z.number().int().positive().max(200).default(15),
  allowDestructive: z.boolean().default(false),
  timeoutSec: z.number().int().positive().max(3600).default(180),
});
export type Constraints = z.infer<typeof ConstraintsSchema>;

export const TargetSchema = z.object({
  platform: PlatformSchema,
  deviceId: z.string().optional(),
});
export type Target = z.infer<typeof TargetSchema>;

/** Body for POST /v1/agent/tasks. */
export const CreateTaskSchema = z.object({
  agentId: z.string().min(1),
  goal: z.string().min(1),
  target: TargetSchema,
  constraints: ConstraintsSchema.partial().optional(),
  skills: z.array(z.string()).default([]),
});
export type CreateTaskRequest = z.infer<typeof CreateTaskSchema>;

/** A fully-materialized task as stored and assigned. */
export interface AgentTask {
  id: string;
  agentId: string;
  goal: string;
  target: Target;
  constraints: Constraints;
  skills: string[];
  status: TaskStatus;
  summary?: string;
  createdAt: number;
  updatedAt: number;
}

// --- grounded actions ------------------------------------------------------

// Every targeting action references an elementId from the current observation;
// the runner rejects any action whose id isn't present (anti-hallucination).
export const ActionSchema = z.discriminatedUnion('type', [
  z.object({ type: z.literal('tap'), elementId: z.string(), reason: z.string().optional() }),
  z.object({
    type: z.literal('type'),
    elementId: z.string(),
    text: z.string(),
    reason: z.string().optional(),
  }),
  z.object({
    type: z.literal('swipe'),
    direction: z.enum(['up', 'down', 'left', 'right']),
    reason: z.string().optional(),
  }),
  z.object({
    type: z.literal('key'),
    key: z.enum(['back', 'home', 'enter', 'recents']),
    reason: z.string().optional(),
  }),
  z.object({ type: z.literal('wait'), ms: z.number().int().nonnegative(), reason: z.string().optional() }),
  z.object({ type: z.literal('done'), reason: z.string().optional() }),
  z.object({ type: z.literal('escalate'), reason: z.string() }),
]);
export type AgentAction = z.infer<typeof ActionSchema>;

/** Actions that mutate external state irreversibly — gated by allowDestructive. */
export const DESTRUCTIVE_HINTS = [
  'delete',
  'remove',
  'uninstall',
  'buy',
  'purchase',
  'pay',
  'send',
  'confirm order',
  'factory reset',
] as const;

// --- WebSocket protocol ----------------------------------------------------

export const ClientMessageSchema = z.discriminatedUnion('type', [
  z.object({
    type: z.literal('hello'),
    agentId: z.string(),
    capabilities: z.array(z.string()).default([]),
    platforms: z.array(PlatformSchema).default([]),
  }),
  z.object({ type: z.literal('status'), taskId: z.string(), state: z.string(), step: z.number().int() }),
  z.object({
    type: z.literal('observation'),
    taskId: z.string(),
    step: z.number().int(),
    screenshotId: z.string().optional(),
    elementCount: z.number().int(),
    packageName: z.string().optional(),
    hash: z.string(),
  }),
  z.object({
    type: z.literal('action'),
    taskId: z.string(),
    step: z.number().int(),
    action: ActionSchema,
    result: z.object({ ok: z.boolean(), detail: z.string().optional() }),
    latencyMs: z.number().nonnegative(),
  }),
  z.object({
    type: z.literal('escalation'),
    taskId: z.string(),
    reason: z.string(),
    options: z.array(z.string()).optional(),
  }),
  z.object({
    type: z.literal('result'),
    taskId: z.string(),
    status: z.enum(['succeeded', 'failed']),
    summary: z.string(),
    steps: z.number().int(),
  }),
  z.object({ type: z.literal('heartbeat'), ts: z.number() }),
]);
export type ClientMessage = z.infer<typeof ClientMessageSchema>;

export type ServerMessage =
  | { type: 'assign'; task: AgentTask }
  | { type: 'control'; taskId: string; command: 'pause' | 'resume' | 'cancel' | 'answer'; payload?: unknown }
  | { type: 'ack'; ref?: string }
  | { type: 'error'; error: string };

// --- helpers ---------------------------------------------------------------

/**
 * Ground an action against the elements actually present on screen. Returns an
 * error string when the action targets an id that doesn't exist — the single
 * most important guard against hallucinated actions.
 */
export function groundAction(action: AgentAction, validElementIds: Set<string>): string | null {
  if ((action.type === 'tap' || action.type === 'type') && !validElementIds.has(action.elementId)) {
    return `action targets unknown elementId "${action.elementId}"`;
  }
  return null;
}

/** Heuristic: does this action look destructive (and thus need allowDestructive)? */
export function isLikelyDestructive(action: AgentAction, elementLabel: string | undefined): boolean {
  const haystack = `${action.reason ?? ''} ${elementLabel ?? ''}`.toLowerCase();
  return DESTRUCTIVE_HINTS.some((hint) => haystack.includes(hint));
}
