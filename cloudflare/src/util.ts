import type { Priority } from './types';

/** Constant-time string comparison to avoid auth-token timing leaks. */
export function timingSafeEqual(a: string, b: string): boolean {
  const enc = new TextEncoder();
  const ab = enc.encode(a);
  const bb = enc.encode(b);
  if (ab.length !== bb.length) return false;
  let diff = 0;
  for (let i = 0; i < ab.length; i++) diff |= ab[i] ^ bb[i];
  return diff === 0;
}

const PRIORITIES: Priority[] = ['LOW', 'NORMAL', 'HIGH'];

/** Coerce arbitrary input to a valid Priority, defaulting to NORMAL. */
export function normalizePriority(value: unknown): Priority {
  return PRIORITIES.includes(value as Priority) ? (value as Priority) : 'NORMAL';
}

/** Parse a Bearer token out of an Authorization header. */
export function bearerToken(header: string | undefined): string {
  if (!header) return '';
  return header.startsWith('Bearer ') ? header.slice('Bearer '.length).trim() : '';
}
