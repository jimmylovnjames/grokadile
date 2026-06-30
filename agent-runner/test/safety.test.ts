import { describe, it, expect } from 'vitest';
import { SafetyGate } from '../src/safety';

describe('SafetyGate', () => {
  it('blocks destructive actions when not allowed', () => {
    const gate = new SafetyGate(false);
    expect(gate.check({ type: 'tap', elementId: 'e1', reason: 'delete the file' }, 'Delete').allowed).toBe(false);
    expect(gate.check({ type: 'tap', elementId: 'e1' }, 'Confirm purchase').allowed).toBe(false);
  });

  it('allows destructive actions when explicitly permitted', () => {
    const gate = new SafetyGate(true);
    expect(gate.check({ type: 'tap', elementId: 'e1', reason: 'delete' }, 'Delete').allowed).toBe(true);
  });

  it('allows benign actions', () => {
    const gate = new SafetyGate(false);
    expect(gate.check({ type: 'tap', elementId: 'e1', reason: 'open settings' }, 'Settings').allowed).toBe(true);
  });
});
