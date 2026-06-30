import { describe, it, expect } from 'vitest';
import { initialContext, isTerminal, transition } from '../src/stateMachine';

describe('state machine', () => {
  it('starts at observe', () => {
    expect(transition('init', { type: 'START' }, initialContext(10)).state).toBe('observe');
  });

  it('routes ungrounded/blocked actions to recover', () => {
    expect(transition('validate', { type: 'ACTION_INVALID' }, initialContext(10)).state).toBe('recover');
    expect(transition('validate', { type: 'ACTION_BLOCKED' }, initialContext(10)).state).toBe('recover');
  });

  it('recovers within budget, escalates beyond it', () => {
    const within = transition('recover', { type: 'TICK' }, { step: 0, maxSteps: 10, recoveries: 0, maxRecoveries: 3 });
    expect(within.state).toBe('observe');
    expect(within.context.recoveries).toBe(1);

    const beyond = transition('recover', { type: 'TICK' }, { step: 0, maxSteps: 10, recoveries: 3, maxRecoveries: 3 });
    expect(beyond.state).toBe('escalate');
  });

  it('terminates on a done action', () => {
    expect(transition('validate', { type: 'ACTION_DONE' }, initialContext(10)).state).toBe('done');
    expect(isTerminal('done')).toBe(true);
  });

  it('consumes a step on verify and fails at maxSteps', () => {
    const r = transition('verify', { type: 'VERIFIED_PROGRESS' }, { step: 4, maxSteps: 5, recoveries: 2, maxRecoveries: 3 });
    expect(r.context.step).toBe(5);
    expect(r.state).toBe('failed');
  });

  it('resets the recovery budget after a verified step', () => {
    const r = transition('verify', { type: 'VERIFIED_NO_CHANGE' }, { step: 1, maxSteps: 5, recoveries: 2, maxRecoveries: 3 });
    expect(r.state).toBe('observe');
    expect(r.context.recoveries).toBe(0);
  });

  it('routes any ERROR to recover', () => {
    expect(transition('act', { type: 'ERROR' }, initialContext(10)).state).toBe('recover');
  });
});
