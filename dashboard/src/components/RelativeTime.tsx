import { useEffect, useState } from 'react'
import { formatDateTime, relativeTime } from '../lib/format'

const TICK_MS = 15_000

/** Live-updating relative timestamp with the absolute time on hover. */
export function RelativeTime({ value }: { value: string }) {
  const now = useNow(TICK_MS)
  const date = new Date(value)
  return (
    <time dateTime={value} title={formatDateTime(date)}>
      {relativeTime(date, now)}
    </time>
  )
}

function useNow(intervalMs: number): Date {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), intervalMs)
    return () => clearInterval(timer)
  }, [intervalMs])
  return now
}
