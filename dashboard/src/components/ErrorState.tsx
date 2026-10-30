import { Card } from './Card'

interface ErrorStateProps {
  onRetry: () => void
  isRetrying?: boolean
}

export function ErrorState({ onRetry, isRetrying = false }: ErrorStateProps) {
  return (
    <Card className="px-6 py-12 text-center">
      <p className="font-medium">Couldn't load data</p>
      <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
        The API is unreachable or returned an error. Check that the api-service is running, then try
        again.
      </p>
      <button
        type="button"
        onClick={onRetry}
        disabled={isRetrying}
        className="mt-4 rounded-md bg-zinc-900 px-3 py-1.5 text-sm font-medium text-white hover:bg-zinc-700 disabled:opacity-60 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
      >
        {isRetrying ? 'Retrying…' : 'Retry'}
      </button>
    </Card>
  )
}
