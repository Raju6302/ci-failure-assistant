import { useQuery } from '@tanstack/react-query'
import { isInProgress } from '../lib/status'
import { getJson } from './client'

export type RunStatus = 'RECEIVED' | 'QUEUED' | 'INGESTING' | 'INGESTED' | 'INGEST_FAILED'

/** What kind of problem failed a job, as decided by the worker's rule-based analysis. */
export type FailureCategory =
  | 'COMPILE_ERROR'
  | 'TEST_FAILURE'
  | 'DEPENDENCY'
  | 'FLAKY_TEST'
  | 'INFRASTRUCTURE'
  | 'CONFIG_ERROR'
  | 'UNKNOWN'

/** A repo-relative file, and line when known, that the failure points at. */
export interface CodeReference {
  path: string
  line: number | null
}

export interface FailureAnalysis {
  category: FailureCategory
  /** The step as GitHub names it, e.g. `Build and test`. */
  failedStep: string | null
  /** One line naming the actual error. */
  summary: string
  /** The relevant log lines, without timestamps or color codes. */
  excerpt: string
  /** 1-based line of the job log where the excerpt starts; null when built from annotations (expired log). */
  excerptStartLine: number | null
  excerptEndLine: number | null
  codeReferences: CodeReference[]
  /** Equal for the same failure on another commit. */
  fingerprint: string
}

export interface WorkflowRun {
  id: number
  repository: string
  workflowName: string
  branch: string
  headSha: string
  runNumber: number
  runAttempt: number
  event: string
  /** GitHub conclusion, e.g. `failure`, `timed_out`, `startup_failure`. */
  conclusion: string
  status: RunStatus
  failedJobCount: number
  htmlUrl: string
  receivedAt: string
  ingestedAt: string | null
  errorMessage: string | null
  /** Category of the run's first failed job; null until it is analyzed. */
  category: FailureCategory | null
  /** That job's one-line error summary. */
  summary: string | null
}

export interface FailedJob {
  id: number
  githubJobId: number
  name: string
  conclusion: string
  failedSteps: string[]
  /** 0 when GitHub no longer has the log (logs expire after 90 days by default). */
  logBytes: number
  logTruncated: boolean
  htmlUrl: string | null
  startedAt: string | null
  completedAt: string | null
  /** Null until the worker has analyzed the job. */
  analysis: FailureAnalysis | null
}

export interface WorkflowRunDetail extends WorkflowRun {
  jobs: FailedJob[]
}

const POLL_INTERVAL_MS = 5_000
const DEFAULT_LIMIT = 50

export const runKeys = {
  all: ['runs'] as const,
  list: (limit: number) => [...runKeys.all, 'list', limit] as const,
  detail: (id: number) => [...runKeys.all, 'detail', id] as const,
}

export function fetchRuns(limit = DEFAULT_LIMIT, signal?: AbortSignal): Promise<WorkflowRun[]> {
  return getJson(`/api/runs?limit=${limit}`, signal)
}

export function fetchRun(id: number, signal?: AbortSignal): Promise<WorkflowRunDetail> {
  return getJson(`/api/runs/${id}`, signal)
}

export function useRuns(limit = DEFAULT_LIMIT) {
  return useQuery({
    queryKey: runKeys.list(limit),
    queryFn: ({ signal }) => fetchRuns(limit, signal),
    refetchInterval: POLL_INTERVAL_MS,
  })
}

export function useRun(id: number) {
  return useQuery({
    queryKey: runKeys.detail(id),
    queryFn: ({ signal }) => fetchRun(id, signal),
    // Keep polling only while the pipeline is still working on this run.
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return status && isInProgress(status) ? POLL_INTERVAL_MS : false
    },
  })
}
