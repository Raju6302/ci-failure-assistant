import type { FailureCategory } from '../api/runs'

interface CategoryAppearance {
  label: string
  /** Badge colors for light and dark mode; each pairing keeps text contrast above 4.5:1. */
  className: string
}

// One hue per category so they are told apart at a glance; the label carries the meaning.
const APPEARANCE: Record<FailureCategory, CategoryAppearance> = {
  COMPILE_ERROR: {
    label: 'Compile error',
    className: 'bg-rose-50 text-rose-800 ring-rose-200 dark:bg-rose-950/60 dark:text-rose-300 dark:ring-rose-900',
  },
  TEST_FAILURE: {
    label: 'Test failure',
    className:
      'bg-orange-50 text-orange-800 ring-orange-200 dark:bg-orange-950/60 dark:text-orange-300 dark:ring-orange-900',
  },
  DEPENDENCY: {
    label: 'Dependency',
    className:
      'bg-violet-50 text-violet-800 ring-violet-200 dark:bg-violet-950/60 dark:text-violet-300 dark:ring-violet-900',
  },
  FLAKY_TEST: {
    label: 'Flaky test',
    className: 'bg-amber-50 text-amber-800 ring-amber-200 dark:bg-amber-950/60 dark:text-amber-300 dark:ring-amber-900',
  },
  INFRASTRUCTURE: {
    label: 'Infrastructure',
    className: 'bg-sky-50 text-sky-800 ring-sky-200 dark:bg-sky-950/60 dark:text-sky-300 dark:ring-sky-900',
  },
  CONFIG_ERROR: {
    label: 'Config error',
    className: 'bg-teal-50 text-teal-800 ring-teal-200 dark:bg-teal-950/60 dark:text-teal-300 dark:ring-teal-900',
  },
  UNKNOWN: {
    label: 'Unknown',
    className: 'bg-zinc-100 text-zinc-700 ring-zinc-200 dark:bg-zinc-800 dark:text-zinc-300 dark:ring-zinc-700',
  },
}

export function categoryAppearance(category: FailureCategory): CategoryAppearance {
  return APPEARANCE[category]
}
