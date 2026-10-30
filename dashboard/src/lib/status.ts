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
  INGESTED: { label: 'Analyzed', tone: 'info' },
  INGEST_FAILED: { label: 'Ingest failed', tone: 'danger' },
  DIAGNOSING: { label: 'Diagnosing', tone: 'info' },
  DIAGNOSED: { label: 'Diagnosed', tone: 'success' },
  DIAGNOSIS_FAILED: { label: 'Diagnosis failed', tone: 'danger' },
}

export function statusAppearance(status: RunStatus): StatusAppearance {
  return APPEARANCE[status]
}

/** True while the backend pipeline is still processing the run. */
export function isInProgress(status: RunStatus): boolean {
  return (
    status === 'RECEIVED' ||
    status === 'QUEUED' ||
    status === 'INGESTING' ||
    status === 'INGESTED' ||
    status === 'DIAGNOSING'
  )
}

/** Heading for the run's error message, which depends on the stage that reported it. */
export function errorTitle(status: RunStatus): string {
  if (status === 'INGEST_FAILED') return 'Ingestion failed'
  if (status === 'DIAGNOSIS_FAILED') return 'Diagnosis failed'
  return 'Last attempt failed; retrying'
}
