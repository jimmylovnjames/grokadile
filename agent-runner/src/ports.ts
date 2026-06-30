import type { AgentAction } from './contract';
import type { Observation } from './types';

/** Captures the current screen as a structured, grounded [Observation]. */
export interface Observer {
  observe(): Promise<Observation>;
}

export interface ActionResult {
  ok: boolean;
  detail?: string;
}

/** Performs a grounded action on the target (ADB / Playwright impls). */
export interface Executor {
  perform(action: AgentAction, observation: Observation): Promise<ActionResult>;
}

/** Proposes the next action from goal + current observation + short history. */
export interface Reasoner {
  plan(input: { goal: string; observation: Observation; history: string[] }): Promise<AgentAction | null>;
}

/** Cheap, deterministic success check between two observations (no LLM). */
export interface Verifier {
  verify(before: Observation, after: Observation): { progressed: boolean };
}

/** Sink for the action trace / status / screenshots (Worker WS impl). */
export interface Telemetry {
  status(step: number, state: string): Promise<void> | void;
  observation(observation: Observation, step: number): Promise<void> | void;
  action(action: AgentAction, step: number, result: ActionResult, latencyMs: number): Promise<void> | void;
  escalation(reason: string): Promise<void> | void;
  result(status: 'succeeded' | 'failed', summary: string, steps: number): Promise<void> | void;
}
