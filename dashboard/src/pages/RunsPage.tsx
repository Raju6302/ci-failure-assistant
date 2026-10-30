import { useRuns } from '../api/runs'
import { EmptyState } from '../components/EmptyState'
import { ErrorState } from '../components/ErrorState'
import { PageHeader } from '../components/PageHeader'
import { RunsTable, RunsTableSkeleton } from '../components/RunsTable'

export function RunsPage() {
  return (
    <section>
      <PageHeader title="Failed runs" description="Newest first. Refreshes every 5 seconds." />
      <RunsContent />
    </section>
  )
}

function RunsContent() {
  const { data: runs, isPending, isRefetchError, isFetching, refetch } = useRuns()

  if (isPending) return <RunsTableSkeleton />
  if (!runs) return <ErrorState onRetry={() => void refetch()} isRetrying={isFetching} />
  if (runs.length === 0) {
    return (
      <EmptyState title="No failed runs yet">
        Trigger the <em>Sandbox failures</em> workflow on GitHub, or see{' '}
        <code className="font-mono text-xs">docs/local-development.md</code>.
      </EmptyState>
    )
  }
  return (
    <>
      {isRefetchError && (
        <p className="mb-3 text-sm text-amber-700 dark:text-amber-400">
          Live updates paused: the API isn't responding. Showing the last data received.
        </p>
      )}
      <RunsTable runs={runs} />
    </>
  )
}
