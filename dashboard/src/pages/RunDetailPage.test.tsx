import { screen, within } from '@testing-library/react'
import { Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { jsonResponse, makeAnalysis, makeJob, makeRun } from '../test/fixtures'
import { renderWithProviders } from '../test/render'
import { RunDetailPage } from './RunDetailPage'

function renderAt(route: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/runs/:id" element={<RunDetailPage />} />
    </Routes>,
    { route },
  )
}

describe('RunDetailPage', () => {
  it('renders the run summary and its failed jobs', async () => {
    const run = { ...makeRun(), jobs: [makeJob({ logTruncated: true })] }
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(run)))

    renderAt('/runs/12')

    expect(await screen.findByText('java-test-failure')).toBeInTheDocument()
    expect(screen.getByText('Run tests')).toBeInTheDocument()
    expect(screen.getByText('Log 47.1 KB')).toBeInTheDocument()
    expect(screen.getByText('Duration 48s')).toBeInTheDocument()
    expect(screen.getByText('truncated')).toBeInTheDocument()
    expect(screen.getByText('Failed jobs (1)')).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith('/api/runs/12', expect.anything())
  })

  it('shows the category, error summary, excerpt with log line numbers and code references', async () => {
    const run = { ...makeRun(), jobs: [makeJob({ analysis: makeAnalysis() })] }
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(run)))

    renderAt('/runs/12')

    expect(await screen.findByText('Test failure')).toBeInTheDocument()
    expect(
      screen.getByText('PriceCalculatorTest.appliesPercentageDiscount: expected <90.0> but was <-900.0>'),
    ).toBeInTheDocument()
    expect(screen.getByText('Log lines 208–211')).toBeInTheDocument()

    const excerpt = screen.getByRole('region', { name: 'Error excerpt, log lines 208 to 211' })
    expect(within(excerpt).getByText('208')).toBeInTheDocument()
    expect(within(excerpt).getByText('211')).toBeInTheDocument()
    expect(within(excerpt).getByText('[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0')).toBeInTheDocument()

    expect(
      screen.getByText('sandbox-repos/java-test-failure/src/test/java/shop/PriceCalculatorTest.java:13'),
    ).toBeInTheDocument()
    expect(screen.getByText('pom.xml')).toBeInTheDocument()
    expect(screen.queryByText('Not analyzed yet.')).not.toBeInTheDocument()
  })

  it('shows an excerpt from annotations without line numbers when the log has expired', async () => {
    const analysis = makeAnalysis({
      category: 'UNKNOWN',
      summary: 'src/app.js:3: Unexpected token',
      excerpt: 'src/app.js:3: Unexpected token',
      excerptStartLine: null,
      excerptEndLine: null,
      codeReferences: [],
    })
    const run = { ...makeRun(), jobs: [makeJob({ logBytes: 0, analysis })] }
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(run)))

    renderAt('/runs/12')

    expect(await screen.findByText('Unknown')).toBeInTheDocument()
    expect(screen.getByText('From the job annotations (log no longer available)')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Error excerpt' })).toHaveTextContent('src/app.js:3: Unexpected token')
    expect(screen.queryByText('Code references')).not.toBeInTheDocument()
  })

  it('says when a job has not been analyzed yet and keeps the diagnosis placeholder', async () => {
    const run = { ...makeRun(), jobs: [makeJob()] }
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(run)))

    renderAt('/runs/12')

    expect(await screen.findByText('Not analyzed yet.')).toBeInTheDocument()
    expect(screen.queryByText('Code references')).not.toBeInTheDocument()
    expect(screen.getByText(/AI diagnosis arrives in Phase 4/)).toBeInTheDocument()
  })

  it('shows the ingestion error when present', async () => {
    const run = { ...makeRun({ status: 'INGEST_FAILED', errorMessage: 'Log download failed: 403' }), jobs: [] }
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(run)))

    renderAt('/runs/12')

    expect(await screen.findByRole('alert')).toHaveTextContent('Log download failed: 403')
  })

  it('shows not found for a 404', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({}, 404)))

    renderAt('/runs/999')

    expect(await screen.findByText('Run not found')).toBeInTheDocument()
  })

  it('shows not found for a non-numeric id without calling the API', () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>())

    renderAt('/runs/abc')

    expect(screen.getByText('Run not found')).toBeInTheDocument()
    expect(fetch).not.toHaveBeenCalled()
  })
})
