import type { RunStatus } from '../api/runs'
import { isInProgress, statusAppearance, type StatusTone } from '../lib/status'

const TONE_CLASSES: Record<StatusTone, { badge: string; dot: string }> = {
  neutral: {
    badge: 'bg-zinc-100 text-zinc-700 ring-zinc-200 dark:bg-zinc-800 dark:text-zinc-300 dark:ring-zinc-700',
    dot: 'bg-zinc-400',
  },
  info: {
    badge: 'bg-blue-50 text-blue-700 ring-blue-200 dark:bg-blue-950/60 dark:text-blue-300 dark:ring-blue-900',
    dot: 'bg-blue-500',
  },
  success: {
    badge:
      'bg-emerald-50 text-emerald-700 ring-emerald-200 dark:bg-emerald-950/60 dark:text-emerald-300 dark:ring-emerald-900',
    dot: 'bg-emerald-500',
  },
  danger: {
    badge: 'bg-red-50 text-red-700 ring-red-200 dark:bg-red-950/60 dark:text-red-300 dark:ring-red-900',
    dot: 'bg-red-500',
  },
}

export function StatusBadge({ status }: { status: RunStatus }) {
  const { label, tone } = statusAppearance(status)
  const classes = TONE_CLASSES[tone]
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap ring-1 ring-inset ${classes.badge}`}
    >
      <span
        aria-hidden="true"
        className={`size-1.5 rounded-full ${classes.dot} ${isInProgress(status) ? 'motion-safe:animate-pulse' : ''}`}
      />
      {label}
    </span>
  )
}
