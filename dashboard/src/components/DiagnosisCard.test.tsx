import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { makeDiagnosis, makeJob } from '../test/fixtures'
import { DiagnosisCard } from './DiagnosisCard'

describe('DiagnosisCard', () => {
  it('shows how an agent diagnosis was made', () => {
    render(<DiagnosisCard job={makeJob()} diagnosis={makeDiagnosis()} />)

    expect(screen.getByText('AI agent · claude-opus-5-5 · 3 steps · 41s · $0.066')).toBeInTheDocument()
    expect(screen.getByText('log line 208')).toBeInTheDocument()
    expect(screen.getByText('Divide the percentage by 100 before subtracting it from 1.')).toBeInTheDocument()
  })

  it('colors added and removed patch lines', () => {
    render(<DiagnosisCard job={makeJob()} diagnosis={makeDiagnosis()} />)

    expect(screen.getByText('+ return price * (1 - percent / 100);')).toHaveClass('bg-emerald-50')
    expect(screen.getByText('- return price * (1 - percent);')).toHaveClass('bg-rose-50')
  })

  it('explains a rule-based fallback and omits the patch', () => {
    const diagnosis = makeDiagnosis({
      source: 'RULES',
      confidence: 'LOW',
      patch: null,
      model: null,
      agentError: 'No Anthropic credentials configured (set ANTHROPIC_API_KEY)',
    })
    render(<DiagnosisCard job={makeJob()} diagnosis={diagnosis} />)

    expect(screen.getByText(/Rule-based diagnosis \(AI agent unavailable: No Anthropic credentials/)).toBeInTheDocument()
    expect(screen.getByText('Low confidence')).toBeInTheDocument()
    expect(screen.queryByText('Patch')).not.toBeInTheDocument()
  })
})
