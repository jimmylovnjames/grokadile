import { isLikelyDestructive, type AgentAction } from './contract';

export interface SafetyDecision {
  allowed: boolean;
  reason?: string;
}

/**
 * Gate that blocks irreversible/destructive actions unless explicitly allowed.
 * Grounded in the action's stated reason and the targeted element's label, so it
 * catches "tap Delete", "Confirm purchase", etc. before they execute.
 */
export class SafetyGate {
  constructor(private readonly allowDestructive: boolean) {}

  check(action: AgentAction, elementLabel: string | undefined): SafetyDecision {
    if (!this.allowDestructive && isLikelyDestructive(action, elementLabel)) {
      const why = 'reason' in action && action.reason ? action.reason : action.type;
      return { allowed: false, reason: `blocked potentially destructive action: ${why}` };
    }
    return { allowed: true };
  }
}
