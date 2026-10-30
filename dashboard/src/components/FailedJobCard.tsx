import type { FailedJob, FailureAnalysis } from '../api/runs'
import { formatBytes, formatCodeLocation, formatDuration } from '../lib/format'
import { Card } from './Card'
import { CategoryBadge } from './CategoryBadge'
import { Conclusion } from './Conclusion'
import { ExternalLink } from './ExternalLink'
import { LogExcerpt } from './LogExcerpt'

export function FailedJobCard({ job }: { job: FailedJob }) {
  const durationMs =
    job.startedAt && job.completedAt
      ? new Date(job.completedAt).getTime() - new Date(job.startedAt).getTime()
      : null

  return (
    <Card className="p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="flex flex-wrap items-center gap-x-2 gap-y-1 font-medium break-words">
            {job.name}
            {job.analysis && <CategoryBadge category={job.analysis.category} />}
          </h3>
          <p className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-zinc-500 dark:text-zinc-400">
            <Conclusion value={job.conclusion} />
            {durationMs !== null && <span>Duration {formatDuration(durationMs)}</span>}
            <span>{job.logBytes > 0 ? `Log ${formatBytes(job.logBytes)}` : 'Log unavailable'}</span>
            {job.logTruncated && (
              <span className="rounded bg-amber-100 px-1.5 py-0.5 font-medium text-amber-800 dark:bg-amber-950/60 dark:text-amber-300">
                truncated
              </span>
            )}
          </p>
        </div>
        {job.htmlUrl && (
          <ExternalLink href={job.htmlUrl} className="text-sm">
            View job
          </ExternalLink>
        )}
      </div>
      {job.failedSteps.length > 0 && (
        <div className="mt-3">
          <h4 className="text-xs text-zinc-500 dark:text-zinc-400">Failed steps</h4>
          <ul className="mt-1 space-y-1 text-sm">
            {job.failedSteps.map((step) => (
              <li key={step} className="flex items-center gap-2">
                <span aria-hidden="true" className="size-1.5 shrink-0 rounded-full bg-red-500" />
                {step}
              </li>
            ))}
          </ul>
        </div>
      )}
      {job.analysis ? (
        <Analysis analysis={job.analysis} />
      ) : (
        <p className="mt-3 text-xs text-zinc-500 dark:text-zinc-400">Not analyzed yet.</p>
      )}
    </Card>
  )
}

function Analysis({ analysis }: { analysis: FailureAnalysis }) {
  return (
    <div className="mt-3 space-y-3">
      <div>
        <h4 className="text-xs text-zinc-500 dark:text-zinc-400">Error</h4>
        <p className="mt-1 text-sm font-medium break-words">{analysis.summary}</p>
      </div>
      {analysis.excerpt && (
        <div>
          <h4 className="mb-1 text-xs text-zinc-500 dark:text-zinc-400">
            {analysis.excerptStartLine === null
              ? 'From the job annotations (log no longer available)'
              : `Log lines ${analysis.excerptStartLine}–${analysis.excerptEndLine}`}
          </h4>
          <LogExcerpt text={analysis.excerpt} startLine={analysis.excerptStartLine} />
        </div>
      )}
      {analysis.codeReferences.length > 0 && (
        <div>
          <h4 className="text-xs text-zinc-500 dark:text-zinc-400">Code references</h4>
          <ul className="mt-1 space-y-0.5 font-mono text-xs break-all">
            {analysis.codeReferences.map((reference) => (
              <li key={formatCodeLocation(reference)}>{formatCodeLocation(reference)}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
