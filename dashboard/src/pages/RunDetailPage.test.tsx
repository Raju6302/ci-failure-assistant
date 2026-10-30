import { screen } from '@testing-library/react'
import { Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { jsonResponse, makeJob, makeRun } from '../test/fixtures'
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
