import { describe, it, expect } from 'vitest';
import {
  ActionSchema,
  ClientMessageSchema,
  CreateTaskSchema,
  groundAction,
  isLikelyDestructive,
  type AgentAction,
} from '../src/contract';

describe('CreateTaskSchema', () => {
  it('accepts a minimal valid task and applies defaults', () => {
    const parsed = CreateTaskSchema.parse({
      agentId: 'pixel-7',
      goal: 'open settings',
      target: { platform: 'android' },
    });
    expect(parsed.skills).toEqual([]);
  });

  it('rejects an unknown platform', () => {
    const r = CreateTaskSchema.safeParse({
      agentId: 'x',
      goal: 'g',
      target: { platform: 'ios' },
    });
    expect(r.success).toBe(false);
  });
});

describe('ActionSchema', () => {
  it('parses a tap action', () => {
    const a = ActionSchema.parse({ type: 'tap', elementId: 'e3', reason: 'open' });
    expect(a.type).toBe('tap');
  });

  it('rejects a type action missing text', () => {
    expect(ActionSchema.safeParse({ type: 'type', elementId: 'e1' }).success).toBe(false);
  });
});

describe('groundAction (anti-hallucination)', () => {
  const valid = new Set(['e0', 'e1', 'e2']);

  it('passes when the targeted element exists', () => {
    const action: AgentAction = { type: 'tap', elementId: 'e1' };
    expect(groundAction(action, valid)).toBeNull();
  });

  it('fails when the element is hallucinated', () => {
    const action: AgentAction = { type: 'tap', elementId: 'e99' };
    expect(groundAction(action, valid)).toContain('e99');
  });

  it('ignores non-targeting actions', () => {
    expect(groundAction({ type: 'key', key: 'back' }, valid)).toBeNull();
    expect(groundAction({ type: 'done' }, valid)).toBeNull();
  });
});

describe('isLikelyDestructive', () => {
  it('flags delete/purchase by reason or label', () => {
    expect(isLikelyDestructive({ type: 'tap', elementId: 'e1', reason: 'delete account' }, undefined)).toBe(true);
    expect(isLikelyDestructive({ type: 'tap', elementId: 'e1' }, 'Confirm purchase')).toBe(true);
  });

  it('does not flag benign actions', () => {
    expect(isLikelyDestructive({ type: 'tap', elementId: 'e1', reason: 'open menu' }, 'Settings')).toBe(false);
  });
});

describe('ClientMessageSchema', () => {
  it('parses a result message', () => {
    const r = ClientMessageSchema.safeParse({
      type: 'result',
      taskId: 't1',
      status: 'succeeded',
      summary: 'done',
      steps: 4,
    });
    expect(r.success).toBe(true);
  });

  it('rejects an unknown message type', () => {
    expect(ClientMessageSchema.safeParse({ type: 'nope' }).success).toBe(false);
  });
});
