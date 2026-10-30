import { useQuery } from '@tanstack/react-query'
import { isInProgress } from '../lib/status'
import { getJson } from './client'

export type RunStatus = 'RECEIVED' | 'QUEUED' | 'INGESTING' | 'INGESTED' | 'INGEST_FAILED'

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
