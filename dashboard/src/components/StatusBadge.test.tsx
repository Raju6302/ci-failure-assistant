import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { RunStatus } from '../api/runs'
import { isInProgress, statusAppearance } from '../lib/status'
import { StatusBadge } from './StatusBadge'

describe('statusAppearance', () => {
  it.each<[RunStatus, string, string, boolean]>([
    ['RECEIVED', 'Received', 'neutral', true],
    ['QUEUED', 'Queued', 'neutral', true],
    ['INGESTING', 'Ingesting', 'info', true],
    ['INGESTED', 'Ingested', 'success', false],
    ['INGEST_FAILED', 'Ingest failed', 'danger', false],
  ])('maps %s to "%s" (%s)', (status, label, tone, inProgress) => {
    expect(statusAppearance(status)).toEqual({ label, tone })
    expect(isInProgress(status)).toBe(inProgress)
  })
})

describe('StatusBadge', () => {
  it('renders the label with red styling for failed ingestion', () => {
    render(<StatusBadge status="INGEST_FAILED" />)
    expect(screen.getByText('Ingest failed')).toHaveClass('bg-red-50')
  })

  it('renders the label with green styling for ingested runs', () => {
    render(<StatusBadge status="INGESTED" />)
    expect(screen.getByText('Ingested')).toHaveClass('bg-emerald-50')
  })

  it('pulses the indicator only while in progress', () => {
    const { container, rerender } = render(<StatusBadge status="QUEUED" />)
    expect(container.querySelector('.motion-safe\\:animate-pulse')).not.toBeNull()

    rerender(<StatusBadge status="INGESTED" />)
    expect(container.querySelector('.motion-safe\\:animate-pulse')).toBeNull()
  })
})
