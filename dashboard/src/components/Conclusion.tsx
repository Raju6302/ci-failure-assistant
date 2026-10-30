import { formatConclusion } from '../lib/format'

/** A GitHub run/job conclusion; every run shown here failed in some way, hence red. */
export function Conclusion({ value }: { value: string }) {
  return <span className="text-red-600 dark:text-red-400">{formatConclusion(value)}</span>
}
