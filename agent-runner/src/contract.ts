/**
 * Mirror of the agent<->Worker contract action/safety types defined in
 * cloudflare/src/contract.ts. Kept dependency-free (no zod) for the runner core;
 * the WebSocket adapter re-validates server messages at the edge. If you change
 * the action shapes, change them in BOTH places (a shared package can replace
 * this duplication later).
 */

export type Platform = 'android' | 'desktop';

export type AgentAction =
  | { type: 'tap'; elementId: string; reason?: string }
  | { type: 'type'; elementId: string; text: string; reason?: string }
  | { type: 'swipe'; direction: 'up' | 'down' | 'left' | 'right'; reason?: string }
  | { type: 'key'; key: 'back' | 'home' | 'enter' | 'recents'; reason?: string }
  | { type: 'wait'; ms: number; reason?: string }
  | { type: 'done'; reason?: string }
  | { type: 'escalate'; reason: string };

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

/** Reject actions that target an element not present on screen. */
export function groundAction(action: AgentAction, validElementIds: Set<string>): string | null {
  if ((action.type === 'tap' || action.type === 'type') && !validElementIds.has(action.elementId)) {
    return `action targets unknown elementId "${action.elementId}"`;
  }
  return null;
}

export function isLikelyDestructive(action: AgentAction, elementLabel: string | undefined): boolean {
  const reason = 'reason' in action ? (action.reason ?? '') : '';
  const haystack = `${reason} ${elementLabel ?? ''}`.toLowerCase();
  return DESTRUCTIVE_HINTS.some((hint) => haystack.includes(hint));
}
