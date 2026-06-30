/**
 * The deterministic core. The agent loop is a finite state machine; the LLM is
 * only consulted to *propose* an action (PLAN) and its proposal becomes an
 * EVENT that this pure reducer accepts or rejects. Ungrounded/blocked/failed
 * actions deterministically route to RECOVER, and exhausted recoveries route to
 * ESCALATE — so the LLM can never drive the loop off the rails.
 *
 *   init ─START→ observe ─→ plan ─→ validate ─→ act ─→ settle ─→ verify ─┐
 *                  ▲                  │  │  │            (step++)         │
 *                  └──── recover ◄────┘  │  └─ done/escalate             │
 *                         │ (≤max)       └────────── observe ◄───────────┘
 *                         └─(>max)→ escalate
 */

export type RunState =
  | 'init'
  | 'observe'
  | 'plan'
  | 'validate'
  | 'act'
  | 'settle'
  | 'verify'
  | 'recover'
  | 'escalate'
  | 'done'
  | 'failed';

export type RunEvent =
  | { type: 'START' }
  | { type: 'TICK' } // unconditional advance (settle/recover)
  | { type: 'OBSERVED' }
  | { type: 'PLANNED' }
  | { type: 'PLAN_EMPTY' }
  | { type: 'ACTION_VALID' }
  | { type: 'ACTION_INVALID' }
  | { type: 'ACTION_BLOCKED' }
  | { type: 'ACTION_DONE' }
  | { type: 'ACTION_ESCALATE' }
  | { type: 'ACTED_OK' }
  | { type: 'ACTED_FAILED' }
  | { type: 'VERIFIED_PROGRESS' }
  | { type: 'VERIFIED_NO_CHANGE' }
  | { type: 'ERROR' };

export interface RunContext {
  step: number;
  maxSteps: number;
  recoveries: number;
  maxRecoveries: number;
}

export interface Transition {
  state: RunState;
  context: RunContext;
}

export const TERMINAL_STATES: ReadonlySet<RunState> = new Set(['escalate', 'done', 'failed']);

export function isTerminal(state: RunState): boolean {
  return TERMINAL_STATES.has(state);
}

export function initialContext(maxSteps: number, maxRecoveries = 3): RunContext {
  return { step: 0, maxSteps, recoveries: 0, maxRecoveries };
}

export function transition(state: RunState, event: RunEvent, ctx: RunContext): Transition {
  // Any unexpected error anywhere falls back to recovery.
  if (event.type === 'ERROR') return { state: 'recover', context: ctx };

  switch (state) {
    case 'init':
      return event.type === 'START' ? { state: 'observe', context: ctx } : stay(state, ctx);

    case 'observe':
      return event.type === 'OBSERVED' ? { state: 'plan', context: ctx } : stay(state, ctx);

    case 'plan':
      if (event.type === 'PLANNED') return { state: 'validate', context: ctx };
      if (event.type === 'PLAN_EMPTY') return { state: 'recover', context: ctx };
      return stay(state, ctx);

    case 'validate':
      switch (event.type) {
        case 'ACTION_DONE':
          return { state: 'done', context: ctx };
        case 'ACTION_ESCALATE':
          return { state: 'escalate', context: ctx };
        case 'ACTION_VALID':
          return { state: 'act', context: ctx };
        case 'ACTION_INVALID':
        case 'ACTION_BLOCKED':
          return { state: 'recover', context: ctx };
        default:
          return stay(state, ctx);
      }

    case 'act':
      if (event.type === 'ACTED_OK') return { state: 'settle', context: ctx };
      if (event.type === 'ACTED_FAILED') return { state: 'recover', context: ctx };
      return stay(state, ctx);

    case 'settle':
      // Timed pass-through; advance unconditionally.
      return { state: 'verify', context: ctx };

    case 'verify': {
      // A full observe→act→verify cycle just completed: consume a step,
      // reset the recovery budget, and stop if we've hit maxSteps.
      const advanced: RunContext = { ...ctx, step: ctx.step + 1, recoveries: 0 };
      if (advanced.step >= advanced.maxSteps) return { state: 'failed', context: advanced };
      return { state: 'observe', context: advanced };
    }

    case 'recover': {
      const recovered: RunContext = { ...ctx, recoveries: ctx.recoveries + 1 };
      if (recovered.recoveries > recovered.maxRecoveries) {
        return { state: 'escalate', context: recovered };
      }
      return { state: 'observe', context: recovered };
    }

    case 'escalate':
    case 'done':
    case 'failed':
      return stay(state, ctx);
  }
}

function stay(state: RunState, context: RunContext): Transition {
  return { state, context };
}
