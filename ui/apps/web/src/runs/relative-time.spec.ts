import { describe, expect, it } from 'vitest';
import { relativeTime } from './relative-time';

const now = new Date('2026-07-19T12:00:00Z');

describe('relativeTime', () => {
  it('renders under a minute as "just now"', () => {
    expect(relativeTime('2026-07-19T11:59:31Z', now)).toBe('just now');
  });

  it('renders minutes', () => {
    expect(relativeTime('2026-07-19T11:55:00Z', now)).toBe('5 minutes ago');
  });

  it('renders a single unit without the plural', () => {
    expect(relativeTime('2026-07-19T11:59:00Z', now)).toBe('1 minute ago');
    expect(relativeTime('2026-07-19T11:00:00Z', now)).toBe('1 hour ago');
    expect(relativeTime('2026-07-18T12:00:00Z', now)).toBe('1 day ago');
  });

  it('renders hours until a day has passed', () => {
    expect(relativeTime('2026-07-19T05:30:00Z', now)).toBe('6 hours ago');
    expect(relativeTime('2026-07-18T13:00:00Z', now)).toBe('23 hours ago');
  });

  it('renders days beyond that', () => {
    expect(relativeTime('2026-07-12T09:00:00Z', now)).toBe('7 days ago');
  });

  it('clamps a small clock skew into the future to "just now"', () => {
    expect(relativeTime('2026-07-19T12:00:05Z', now)).toBe('just now');
  });
});
