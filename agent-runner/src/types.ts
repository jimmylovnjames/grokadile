import type { Platform } from './contract';

/** A single grounded element from the observation (real bounds/ids, not guessed). */
export interface ObservedElement {
  id: string;
  text?: string;
  description?: string;
  role?: string;
  bounds: { left: number; top: number; right: number; bottom: number };
  clickable: boolean;
  editable: boolean;
}

/** Structured snapshot the reasoner sees. [hash] is used to detect screen change. */
export interface Observation {
  platform: Platform;
  packageName?: string;
  elements: ObservedElement[];
  /** Raw OCR text, when an a11y tree isn't available (canvas/desktop). */
  ocrText?: string;
  /** PNG bytes, kept for failure capture; not required for reasoning. */
  screenshot?: Uint8Array;
  hash: string;
  capturedAt: number;
}

export interface RunnerConfig {
  taskId: string;
  goal: string;
  maxSteps: number;
  allowDestructive: boolean;
  maxRecoveries: number;
  settleMs: number;
}
