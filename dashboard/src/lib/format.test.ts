import { describe, expect, it } from 'vitest'
import { formatBytes, formatConclusion, formatDuration, relativeTime, shortSha } from './format'

describe('shortSha', () => {
  it('keeps the first 7 characters', () => {
    expect(shortSha('3f2c9a1b7e4d5c6b8a9f0e1d2c3b4a5f6e7d8c9b')).toBe('3f2c9a1')
  })
})

describe('formatBytes', () => {
  it.each([
    [0, '0 B'],
    [512, '512 B'],
    [1024, '1 KB'],
    [48213, '47.1 KB'],
    [5 * 1024 * 1024, '5 MB'],
    [1.5 * 1024 ** 3, '1.5 GB'],
  ])('formats %d as %s', (bytes, expected) => {
    expect(formatBytes(bytes)).toBe(expected)
  })
})

describe('formatDuration', () => {
  it.each([
    [0, '0s'],
    [48_000, '48s'],
    [125_000, '2m 5s'],
    [3_840_000, '1h 4m'],
    [-5_000, '0s'],
  ])('formats %d ms as %s', (ms, expected) => {
    expect(formatDuration(ms)).toBe(expected)
  })
})

describe('relativeTime', () => {
  const now = new Date('2026-10-30T12:00:00Z')
  const ago = (ms: number) => new Date(now.getTime() - ms)

  it.each([
    [0, 'now'],
    [12_000, '12 seconds ago'],
    [59_600, '59 seconds ago'],
    [2 * 60_000, '2 minutes ago'],
    [3 * 3_600_000, '3 hours ago'],
    [26 * 3_600_000, 'yesterday'],
    [14 * 86_400_000, '2 weeks ago'],
    [400 * 86_400_000, 'last year'],
  ])('formats %d ms ago as %s', (ms, expected) => {
    expect(relativeTime(ago(ms), now)).toBe(expected)
  })

  it('handles timestamps slightly in the future', () => {
    expect(relativeTime(new Date(now.getTime() + 30_000), now)).toBe('in 30 seconds')
  })
})

describe('formatConclusion', () => {
  it.each([
    ['failure', 'Failure'],
    ['timed_out', 'Timed out'],
    ['startup_failure', 'Startup failure'],
  ])('formats %s as %s', (conclusion, expected) => {
    expect(formatConclusion(conclusion)).toBe(expected)
  })
})
