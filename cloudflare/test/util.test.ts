import { describe, it, expect } from 'vitest';
import { timingSafeEqual, normalizePriority, bearerToken } from '../src/util';

describe('timingSafeEqual', () => {
  it('matches equal strings', () => {
    expect(timingSafeEqual('abc123', 'abc123')).toBe(true);
  });
  it('rejects different strings', () => {
    expect(timingSafeEqual('abc123', 'abc124')).toBe(false);
  });
  it('rejects different lengths', () => {
    expect(timingSafeEqual('abc', 'abcd')).toBe(false);
  });
});

describe('normalizePriority', () => {
  it('keeps valid values', () => {
    expect(normalizePriority('HIGH')).toBe('HIGH');
    expect(normalizePriority('LOW')).toBe('LOW');
  });
  it('defaults invalid values to NORMAL', () => {
    expect(normalizePriority('urgent')).toBe('NORMAL');
    expect(normalizePriority(undefined)).toBe('NORMAL');
    expect(normalizePriority(42)).toBe('NORMAL');
  });
});

describe('bearerToken', () => {
  it('extracts the token', () => {
    expect(bearerToken('Bearer xyz')).toBe('xyz');
  });
  it('returns empty for missing/!bearer', () => {
    expect(bearerToken(undefined)).toBe('');
    expect(bearerToken('Basic abc')).toBe('');
  });
});
