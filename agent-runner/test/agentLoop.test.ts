import { describe, it, expect } from 'vitest';
import { AgentLoop, type AgentDeps } from '../src/agentLoop';
import { SafetyGate } from '../src/safety';
import { ReplayBuffer } from '../src/replayBuffer';
import { ScreenChangeVerifier } from '../src/verifier';
import { NullLogger } from '../src/logger';
import type { AgentAction } from '../src/contract';
import type { Observation } from '../src/types';
import type { Executor, Observer, Reasoner, Telemetry } from '../src/ports';

function makeObservation(hash: string, ids: string[] = ['e0']): Observation {
  return {
    platform: 'android',
    elements: ids.map((id) => ({
      id,
      text: id,
      bounds: { left: 0, top: 0, right: 1, bottom: 1 },
      clickable: true,
      editable: false,
    })),
    hash,
    capturedAt: Date.now(),
  };
}

class SeqObserver implements Observer {
  private i = 0;
  constructor(private readonly hashes: string[]) {}
  async observe(): Promise<Observation> {
    const hash = this.hashes[Math.min(this.i, this.hashes.length - 1)];
    this.i += 1;
    return makeObservation(hash);
  }
}

class SeqReasoner implements Reasoner {
  private i = 0;
  constructor(private readonly actions: (AgentAction | null)[]) {}
  async plan(): Promise<AgentAction | null> {
    return this.actions[Math.min(this.i++, this.actions.length - 1)];
  }
}

const okExecutor: Executor = { async perform() { return { ok: true }; } };

const nullTelemetry: Telemetry = {
  status() {},
  observation() {},
  action() {},
  escalation() {},
  result() {},
};

function makeDeps(observer: Observer, reasoner: Reasoner): AgentDeps {
  return {
    observer,
    reasoner,
    executor: okExecutor,
    verifier: new ScreenChangeVerifier(),
    safety: new SafetyGate(false),
    telemetry: nullTelemetry,
    replay: new ReplayBuffer(),
    logger: new NullLogger(),
  };
}

describe('AgentLoop', () => {
  it('succeeds immediately when the reasoner says done', async () => {
    const loop = new AgentLoop(
      makeDeps(new SeqObserver(['a']), new SeqReasoner([{ type: 'done', reason: 'arrived' }])),
      { goal: 'g', maxSteps: 5, maxRecoveries: 3, settleMs: 0 },
    );
    expect((await loop.run()).status).toBe('succeeded');
  });

  it('runs a grounded tap step then finishes', async () => {
    const loop = new AgentLoop(
      makeDeps(
        new SeqObserver(['a', 'b', 'b']),
        new SeqReasoner([{ type: 'tap', elementId: 'e0' }, { type: 'done' }]),
      ),
      { goal: 'g', maxSteps: 5, maxRecoveries: 3, settleMs: 0 },
    );
    const out = await loop.run();
    expect(out.status).toBe('succeeded');
    expect(out.steps).toBeGreaterThanOrEqual(1);
  });

  it('escalates after repeated ungrounded actions', async () => {
    const loop = new AgentLoop(
      makeDeps(new SeqObserver(['a']), new SeqReasoner([{ type: 'tap', elementId: 'missing' }])),
      { goal: 'g', maxSteps: 10, maxRecoveries: 2, settleMs: 0 },
    );
    expect((await loop.run()).status).toBe('escalated');
  });
});
