import { Link } from 'react-router'

export function BackLink() {
  return (
    <Link
      to="/"
      className="mb-4 inline-flex items-center gap-1 text-sm text-zinc-500 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-100"
    >
      <span aria-hidden="true">←</span> All runs
    </Link>
  )
}
