import type { AgentAction } from './contract';

export interface ReplayEntry {
  step: number;
  observationHash: string;
  action: AgentAction;
  ok: boolean;
  ts: number;
}

/**
 * Bounded ring buffer of the most recent step outcomes. On failure/escalation
 * the runner ships this for post-mortem replay and debugging.
 */
export class ReplayBuffer {
  private readonly items: ReplayEntry[] = [];

  constructor(private readonly capacity = 50) {}

  push(entry: ReplayEntry): void {
    this.items.push(entry);
    if (this.items.length > this.capacity) this.items.shift();
  }

  snapshot(): ReplayEntry[] {
    return [...this.items];
  }

  clear(): void {
    this.items.length = 0;
  }
}
