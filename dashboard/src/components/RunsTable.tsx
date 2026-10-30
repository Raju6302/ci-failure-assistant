import type { ReactNode } from 'react'
import { Link } from 'react-router'
import type { WorkflowRun } from '../api/runs'
import { shortSha } from '../lib/format'
import { Card } from './Card'
import { CategoryBadge } from './CategoryBadge'
import { Conclusion } from './Conclusion'
import { ExternalLink } from './ExternalLink'
import { RelativeTime } from './RelativeTime'
import { Skeleton } from './Skeleton'
import { StatusBadge } from './StatusBadge'

const HEADERS = [
  { label: 'Repository', className: 'hidden lg:table-cell' },
  { label: 'Workflow' },
  { label: 'Branch', className: 'hidden md:table-cell' },
  { label: 'Commit', className: 'hidden md:table-cell' },
  { label: 'Conclusion', className: 'hidden sm:table-cell' },
  { label: 'Status' },
  { label: 'Failure', className: 'hidden sm:table-cell' },
  { label: 'Failed jobs', className: 'hidden md:table-cell text-right' },
  { label: 'Received', className: 'hidden sm:table-cell' },
  { label: <span className="sr-only">GitHub</span> },
]

const CELL = 'px-4 py-3 whitespace-nowrap'

export function RunsTable({ runs }: { runs: WorkflowRun[] }) {
  return (
    <TableFrame>
      {runs.map((run) => (
        <RunRow key={run.id} run={run} />
      ))}
    </TableFrame>
  )
}

export function RunsTableSkeleton({ rows = 5 }: { rows?: number }) {
  return (
    <TableFrame>
      {Array.from({ length: rows }, (_, index) => (
        <tr key={index}>
          {HEADERS.map((header, column) => (
            <td key={column} className={`${CELL} ${header.className ?? ''}`}>
              <Skeleton className="h-4 w-full max-w-24" />
            </td>
          ))}
        </tr>
      ))}
    </TableFrame>
  )
}

function TableFrame({ children }: { children: ReactNode }) {
  return (
    <Card className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead className="border-b border-zinc-200 text-xs text-zinc-500 dark:border-zinc-800 dark:text-zinc-400">
          <tr>
            {HEADERS.map((header, index) => (
              <th key={index} scope="col" className={`px-4 py-2.5 font-medium whitespace-nowrap ${header.className ?? ''}`}>
                {header.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-zinc-100 dark:divide-zinc-800">{children}</tbody>
      </table>
    </Card>
  )
}

function RunRow({ run }: { run: WorkflowRun }) {
  return (
    // The workflow link stretches over the whole row; the GitHub link sits above it.
    <tr className="relative hover:bg-zinc-50 dark:hover:bg-zinc-800/50">
      <td className={`${CELL} hidden text-zinc-600 lg:table-cell dark:text-zinc-400`}>{run.repository}</td>
      <td className={CELL}>
        <Link
          to={`/runs/${run.id}`}
          className="font-medium after:absolute after:inset-0 focus-visible:outline-none focus-visible:after:ring-2 focus-visible:after:ring-blue-500 focus-visible:after:ring-inset"
        >
          {run.workflowName} <span className="text-zinc-500 dark:text-zinc-400">#{run.runNumber}</span>
        </Link>
        <div className="text-xs text-zinc-500 lg:hidden dark:text-zinc-400">{run.repository}</div>
      </td>
      <td className={`${CELL} hidden md:table-cell`}>{run.branch}</td>
      <td className={`${CELL} hidden font-mono text-xs md:table-cell`} title={run.headSha}>
        {shortSha(run.headSha)}
      </td>
      <td className={`${CELL} hidden sm:table-cell`}>
        <Conclusion value={run.conclusion} />
      </td>
      <td className={CELL}>
        <StatusBadge status={run.status} />
      </td>
      <td className={`${CELL} hidden sm:table-cell`}>
        {run.category ? (
          <>
            <CategoryBadge category={run.category} />
            {run.summary && (
              <div className="mt-1 max-w-xs truncate text-xs text-zinc-500 dark:text-zinc-400" title={run.summary}>
                {run.summary}
              </div>
            )}
          </>
        ) : (
          <span className="text-zinc-400 dark:text-zinc-500">—</span>
        )}
      </td>
      <td className={`${CELL} hidden text-right tabular-nums md:table-cell`}>{run.failedJobCount}</td>
      <td className={`${CELL} hidden text-zinc-500 sm:table-cell dark:text-zinc-400`}>
        <RelativeTime value={run.receivedAt} />
      </td>
      <td className={`${CELL} text-right`}>
        <ExternalLink href={run.htmlUrl} className="relative z-10">
          <span className="sr-only">
            View {run.workflowName} #{run.runNumber} on GitHub
          </span>
        </ExternalLink>
      </td>
    </tr>
  )
}
