import type { Diagnosis, Evidence, FailedJob } from '../api/runs'
import { formatDuration } from '../lib/format'
import { Card } from './Card'
import { CategoryBadge } from './CategoryBadge'

const CONFIDENCE_CLASSES: Record<Diagnosis['confidence'], string> = {
  HIGH: 'bg-emerald-50 text-emerald-800 ring-emerald-200 dark:bg-emerald-950/60 dark:text-emerald-300 dark:ring-emerald-900',
  MEDIUM: 'bg-sky-50 text-sky-800 ring-sky-200 dark:bg-sky-950/60 dark:text-sky-300 dark:ring-sky-900',
  LOW: 'bg-zinc-100 text-zinc-700 ring-zinc-200 dark:bg-zinc-800 dark:text-zinc-300 dark:ring-zinc-700',
}

/** One job's diagnosis: root cause, evidence, suggested fix and (optionally) a patch. */
export function DiagnosisCard({ job, diagnosis }: { job: FailedJob; diagnosis: Diagnosis }) {
  return (
    <Card className="p-4">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="font-medium break-words">{job.name}</h3>
        <CategoryBadge category={diagnosis.category} />
        <span
          className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${CONFIDENCE_CLASSES[diagnosis.confidence]}`}
        >
          {diagnosis.confidence.charAt(0) + diagnosis.confidence.slice(1).toLowerCase()} confidence
        </span>
      </div>
      <Provenance diagnosis={diagnosis} />

      <p className="mt-3 text-sm leading-relaxed">{diagnosis.rootCause}</p>

      {diagnosis.evidence.length > 0 && (
        <div className="mt-4">
          <h4 className="text-xs font-medium text-zinc-500 dark:text-zinc-400">Evidence</h4>
          <ul className="mt-1 space-y-2">
            {diagnosis.evidence.map((item, index) => (
              <EvidenceItem key={index} evidence={item} />
            ))}
          </ul>
        </div>
      )}

      <div className="mt-4">
        <h4 className="text-xs font-medium text-zinc-500 dark:text-zinc-400">Suggested fix</h4>
        <p className="mt-1 text-sm">{diagnosis.suggestedFix}</p>
      </div>

      {diagnosis.patch && (
        <div className="mt-4">
          <h4 className="text-xs font-medium text-zinc-500 dark:text-zinc-400">Patch</h4>
          <Patch diff={diagnosis.patch} />
        </div>
      )}
    </Card>
  )
}

function Provenance({ diagnosis }: { diagnosis: Diagnosis }) {
  if (diagnosis.source === 'RULES') {
    return (
      <p className="mt-1 text-xs text-zinc-500 dark:text-zinc-400">
        Rule-based diagnosis{diagnosis.agentError ? ` (AI agent unavailable: ${diagnosis.agentError})` : ''}
      </p>
    )
  }
  const details = [
    diagnosis.model,
    `${diagnosis.steps} ${diagnosis.steps === 1 ? 'step' : 'steps'}`,
    formatDuration(diagnosis.latencyMs),
    `$${diagnosis.costUsd.toFixed(3)}`,
  ].filter(Boolean)
  return <p className="mt-1 text-xs text-zinc-500 dark:text-zinc-400">AI agent · {details.join(' · ')}</p>
}

function EvidenceItem({ evidence }: { evidence: Evidence }) {
  const location = evidence.path
    ? `${evidence.path}${evidence.line > 0 ? `:${evidence.line}` : ''}`
    : evidence.line > 0
      ? `log line ${evidence.line}`
      : evidence.source
  return (
    <li className="text-sm">
      <span className="font-mono text-xs break-all text-zinc-600 dark:text-zinc-400">{location}</span>
      <pre className="mt-0.5 overflow-x-auto rounded bg-zinc-50 px-2 py-1 font-mono text-xs dark:bg-zinc-950">
        {evidence.quote}
      </pre>
    </li>
  )
}

function Patch({ diff }: { diff: string }) {
  return (
    <pre className="mt-1 overflow-x-auto rounded border border-zinc-200 bg-zinc-50 py-2 font-mono text-xs dark:border-zinc-800 dark:bg-zinc-950">
      {diff.split('\n').map((line, index) => (
        <div key={index} className={`px-3 ${patchLineClass(line)}`}>
          {line || ' '}
        </div>
      ))}
    </pre>
  )
}

function patchLineClass(line: string): string {
  if (line.startsWith('+++') || line.startsWith('---')) return 'text-zinc-500 dark:text-zinc-400'
  if (line.startsWith('+')) return 'bg-emerald-50 text-emerald-800 dark:bg-emerald-950/50 dark:text-emerald-300'
  if (line.startsWith('-')) return 'bg-rose-50 text-rose-800 dark:bg-rose-950/50 dark:text-rose-300'
  if (line.startsWith('@@')) return 'text-sky-700 dark:text-sky-400'
  return ''
}
