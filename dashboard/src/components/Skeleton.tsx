interface SkeletonProps {
  className?: string
}

export function Skeleton({ className = '' }: SkeletonProps) {
  return <div aria-hidden="true" className={`animate-pulse rounded bg-zinc-200 dark:bg-zinc-800 ${className}`} />
}
