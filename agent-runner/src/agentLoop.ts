import { groundAction, type AgentAction } from './contract';
import type { Observation } from './types';
import type { Executor, Observer, Reasoner, Telemetry, Verifier } from './ports';
import type { SafetyGate } from './safety';
import type { ReplayBuffer } from './replayBuffer';
import type { Logger } from './logger';
import {
  initialContext,
  isTerminal,
  transition,
  type RunContext,
  type RunState,
} from './stateMachine';

export interface AgentDeps {
  observer: Observer;
  reasoner: Reasoner;
  executor: Executor;
  verifier: Verifier;
  safety: SafetyGate;
  telemetry: Telemetry;
  replay: ReplayBuffer;
  logger: Logger;
}

export interface AgentRunConfig {
  goal: string;
  maxSteps: number;
  maxRecoveries: number;
  settleMs: number;
}

export interface RunOutcome {
  status: 'succeeded' | 'failed' | 'escalated';
  steps: number;
  summary: string;
}

const HISTORY_LIMIT = 8;

/**
 * Drives the FSM in stateMachine.ts using the injected ports. Each loop turn:
 * runs the current state's side effect (observe / call reasoner / validate &
 * ground / execute / settle / verify), converts the outcome into a [RunEvent],
 * and lets the pure reducer pick the next state. The LLM only feeds PLAN; every
 * proposal is grounded + safety-checked before it can reach the executor.
 */
export class AgentLoop {
  constructor(
    private readonly deps: AgentDeps,
    private readonly config: AgentRunConfig,
  ) {}

  async run(signal?: AbortSignal): Promise<RunOutcome> {
    const { observer, reasoner, executor, verifier, safety, telemetry, replay, logger } = this.deps;

    let state: RunState = 'init';
    let ctx: RunContext = initialContext(this.config.maxSteps, this.config.maxRecoveries);
    const history: string[] = [];
    let observation: Observation | null = null;
    let before: Observation | null = null;
    let pending: AgentAction | null = null;

    ({ state, context: ctx } = transition(state, { type: 'START' }, ctx));

    while (!isTerminal(state)) {
      if (signal?.aborted) {
        logger.warn('run aborted');
        return this.finish('failed', ctx.step, 'aborted');
      }
      try {
        switch (state) {
          case 'observe': {
            observation = await observer.observe();
            before = observation;
            await telemetry.observation(observation, ctx.step);
            ({ state, context: ctx } = transition(state, { type: 'OBSERVED' }, ctx));
            break;
          }
          case 'plan': {
            pending = await reasoner.plan({
              goal: this.config.goal,
              observation: observation!,
              history,
            });
            ({ state, context: ctx } = transition(
              state,
              pending ? { type: 'PLANNED' } : { type: 'PLAN_EMPTY' },
              ctx,
            ));
            break;
          }
          case 'validate': {
            ({ state, context: ctx } = this.validate(pending!, observation!, ctx, logger, safety));
            break;
          }
          case 'act': {
            const action = pending!;
            const startedAt = Date.now();
            const result = await executor.perform(action, observation!);
            await telemetry.action(action, ctx.step, result, Date.now() - startedAt);
            replay.push({
              step: ctx.step,
              observationHash: observation!.hash,
              action,
              ok: result.ok,
              ts: Date.now(),
            });
            history.push(describeAction(action, result.ok));
            if (history.length > HISTORY_LIMIT) history.shift();
            ({ state, context: ctx } = transition(
              state,
              result.ok ? { type: 'ACTED_OK' } : { type: 'ACTED_FAILED' },
              ctx,
            ));
            break;
          }
          case 'settle': {
            await delay(this.config.settleMs);
            ({ state, context: ctx } = transition(state, { type: 'TICK' }, ctx));
            break;
          }
          case 'verify': {
            const after = await observer.observe();
            const { progressed } = verifier.verify(before!, after);
            observation = after;
            await telemetry.status(ctx.step, 'verify');
            ({ state, context: ctx } = transition(
              state,
              progressed ? { type: 'VERIFIED_PROGRESS' } : { type: 'VERIFIED_NO_CHANGE' },
              ctx,
            ));
            break;
          }
          case 'recover': {
            logger.info('recovering', { recoveries: ctx.recoveries, step: ctx.step });
            ({ state, context: ctx } = transition(state, { type: 'TICK' }, ctx));
            break;
          }
          default: {
            ({ state, context: ctx } = transition(state, { type: 'TICK' }, ctx));
          }
        }
      } catch (err) {
        logger.error('step error', { state, error: String(err) });
        ({ state, context: ctx } = transition(state, { type: 'ERROR' }, ctx));
      }
    }

    const status =
      state === 'done' ? 'succeeded' : state === 'escalate' ? 'escalated' : 'failed';
    return this.finish(status, ctx.step, `${status} after ${ctx.step} step(s)`);
  }

  /** Pure-ish gate: terminal action? grounded? safe? → the next transition. */
  private validate(
    action: AgentAction,
    observation: Observation,
    ctx: RunContext,
    logger: Logger,
    safety: SafetyGate,
  ): { state: RunState; context: RunContext } {
    if (action.type === 'done') return transition('validate', { type: 'ACTION_DONE' }, ctx);
    if (action.type === 'escalate') return transition('validate', { type: 'ACTION_ESCALATE' }, ctx);

    const ids = new Set(observation.elements.map((e) => e.id));
    const groundError = groundAction(action, ids);
    if (groundError) {
      logger.warn('rejected ungrounded action', { action, error: groundError });
      return transition('validate', { type: 'ACTION_INVALID' }, ctx);
    }

    const decision = safety.check(action, labelFor(action, observation));
    if (!decision.allowed) {
      logger.warn('safety blocked action', { action, reason: decision.reason });
      return transition('validate', { type: 'ACTION_BLOCKED' }, ctx);
    }
    return transition('validate', { type: 'ACTION_VALID' }, ctx);
  }

  private async finish(
    status: RunOutcome['status'],
    steps: number,
    summary: string,
  ): Promise<RunOutcome> {
    if (status === 'escalated') await this.deps.telemetry.escalation(summary);
    await this.deps.telemetry.result(status === 'succeeded' ? 'succeeded' : 'failed', summary, steps);
    return { status, steps, summary };
  }
}

function labelFor(action: AgentAction, observation: Observation): string | undefined {
  if (action.type !== 'tap' && action.type !== 'type') return undefined;
  const element = observation.elements.find((e) => e.id === action.elementId);
  return element?.text ?? element?.description;
}

function describeAction(action: AgentAction, ok: boolean): string {
  const target = 'elementId' in action ? ` ${action.elementId}` : '';
  return `${action.type}${target} -> ${ok ? 'ok' : 'fail'}`;
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
