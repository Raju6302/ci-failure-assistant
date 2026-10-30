import type { RunStatus } from '../api/runs'

export type StatusTone = 'neutral' | 'info' | 'success' | 'danger'

interface StatusAppearance {
  label: string
  tone: StatusTone
}

const APPEARANCE: Record<RunStatus, StatusAppearance> = {
  RECEIVED: { label: 'Received', tone: 'neutral' },
  QUEUED: { label: 'Queued', tone: 'neutral' },
  INGESTING: { label: 'Ingesting', tone: 'info' },
  INGESTED: { label: 'Ingested', tone: 'success' },
  INGEST_FAILED: { label: 'Ingest failed', tone: 'danger' },
}

export function statusAppearance(status: RunStatus): StatusAppearance {
  return APPEARANCE[status]
}

/** True while the backend pipeline is still processing the run. */
export function isInProgress(status: RunStatus): boolean {
  return status === 'RECEIVED' || status === 'QUEUED' || status === 'INGESTING'
}
