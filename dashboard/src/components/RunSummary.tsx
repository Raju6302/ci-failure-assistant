import type { ReactNode } from 'react'
import type { WorkflowRun } from '../api/runs'
import { formatDateTime } from '../lib/format'
import { Card } from './Card'
import { Conclusion } from './Conclusion'
import { ExternalLink } from './ExternalLink'
import { RelativeTime } from './RelativeTime'
import { StatusBadge } from './StatusBadge'

export function RunSummary({ run }: { run: WorkflowRun }) {
  return (
    <Card className="p-4 sm:p-6">
      <dl className="grid gap-x-6 gap-y-4 text-sm sm:grid-cols-2 lg:grid-cols-3">
        <Field label="Repository">{run.repository}</Field>
        <Field label="Workflow">{run.workflowName}</Field>
        <Field label="Run">
          #{run.runNumber} · attempt {run.runAttempt}
        </Field>
        <Field label="Branch">{run.branch}</Field>
        <Field label="Commit">
          <ExternalLink
            href={`https://github.com/${run.repository}/commit/${run.headSha}`}
            className="font-mono text-xs break-all"
          >
            {run.headSha}
          </ExternalLink>
        </Field>
        <Field label="Event">{run.event}</Field>
        <Field label="Conclusion">
          <Conclusion value={run.conclusion} />
        </Field>
        <Field label="Pipeline status">
          <StatusBadge status={run.status} />
        </Field>
        <Field label="Failed jobs">{run.failedJobCount}</Field>
        <Field label="Received">
          <Timestamp value={run.receivedAt} />
        </Field>
        <Field label="Ingested">{run.ingestedAt ? <Timestamp value={run.ingestedAt} /> : '—'}</Field>
        <Field label="Run ID">{run.id}</Field>
      </dl>
    </Card>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-xs text-zinc-500 dark:text-zinc-400">{label}</dt>
      <dd className="mt-1 break-words">{children}</dd>
    </div>
  )
}

function Timestamp({ value }: { value: string }) {
  return (
    <>
      {formatDateTime(new Date(value))}{' '}
      <span className="text-zinc-500 dark:text-zinc-400">
        (<RelativeTime value={value} />)
      </span>
    </>
  )
}
