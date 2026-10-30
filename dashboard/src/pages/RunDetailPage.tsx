import type { ReactNode } from 'react'
import { useParams } from 'react-router'
import { isNotFound } from '../api/client'
import { useRun, type WorkflowRunDetail } from '../api/runs'
import { BackLink } from '../components/BackLink'
import { Card } from '../components/Card'
import { EmptyState } from '../components/EmptyState'
import { ErrorState } from '../components/ErrorState'
import { ExternalLink } from '../components/ExternalLink'
import { FailedJobCard } from '../components/FailedJobCard'
import { PageHeader } from '../components/PageHeader'
import { RunSummary } from '../components/RunSummary'
import { Skeleton } from '../components/Skeleton'
import { StatusBadge } from '../components/StatusBadge'
import { isInProgress } from '../lib/status'

export function RunDetailPage() {
  const runId = Number(useParams().id)

  return (
    <section>
      <BackLink />
      {Number.isInteger(runId) && runId > 0 ? <RunDetail runId={runId} /> : <RunNotFound />}
    </section>
  )
}

function RunDetail({ runId }: { runId: number }) {
  const { data: run, error, isPending, isFetching, refetch } = useRun(runId)

  if (isPending) return <RunDetailSkeleton />
  if (!run) {
    return isNotFound(error) ? (
      <RunNotFound />
    ) : (
      <ErrorState onRetry={() => void refetch()} isRetrying={isFetching} />
    )
  }

  return (
    <div className="space-y-8">
      <div>
        <PageHeader
          title={
            <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
              {run.workflowName} #{run.runNumber}
              <StatusBadge status={run.status} />
            </span>
          }
          description={`${run.repository} · ${run.branch}`}
          actions={
            <ExternalLink href={run.htmlUrl} className="text-sm">
              View on GitHub
            </ExternalLink>
          }
        />
        {run.errorMessage && (
          <div
            role="alert"
            className="mb-4 rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-800 dark:border-red-900 dark:bg-red-950/40 dark:text-red-300"
          >
            <p className="font-medium">Ingestion failed</p>
            <p className="mt-1 font-mono text-xs break-words whitespace-pre-wrap">{run.errorMessage}</p>
          </div>
        )}
        <RunSummary run={run} />
      </div>

      <Section title={`Failed jobs (${run.jobs.length})`}>
        <FailedJobs run={run} />
      </Section>

      <Section title="Diagnosis">
        <p className="text-sm text-zinc-500 dark:text-zinc-400">
          AI diagnosis arrives in Phase 4: root cause, evidence and a suggested fix will appear here.
        </p>
      </Section>
    </div>
  )
}

function FailedJobs({ run }: { run: WorkflowRunDetail }) {
  if (run.jobs.length === 0) {
    return (
      <EmptyState
        title={isInProgress(run.status) ? 'Fetching job logs…' : 'No failed jobs recorded for this run'}
      />
    )
  }
  return (
    <div className="space-y-3">
      {run.jobs.map((job) => (
        <FailedJobCard key={job.id} job={job} />
      ))}
    </div>
  )
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section>
      <h2 className="mb-3 text-sm font-semibold">{title}</h2>
      {children}
    </section>
  )
}

function RunNotFound() {
  return <EmptyState title="Run not found">It may have been removed, or the link is wrong.</EmptyState>
}

function RunDetailSkeleton() {
  return (
    <div aria-busy="true" aria-label="Loading run">
      <Skeleton className="mb-2 h-7 w-64" />
      <Skeleton className="mb-6 h-4 w-48" />
      <Card className="grid gap-4 p-6 sm:grid-cols-2 lg:grid-cols-3">
        {Array.from({ length: 6 }, (_, index) => (
          <Skeleton key={index} className="h-9" />
        ))}
      </Card>
    </div>
  )
}
