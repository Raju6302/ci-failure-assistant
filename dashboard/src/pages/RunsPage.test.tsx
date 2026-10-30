import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { jsonResponse, makeRun } from '../test/fixtures'
import { renderWithProviders } from '../test/render'
import { RunsPage } from './RunsPage'

function stubFetch(...responses: Response[]) {
  const fetchMock = vi.fn<typeof fetch>()
  for (const response of responses) fetchMock.mockResolvedValueOnce(response)
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

describe('RunsPage', () => {
  it('renders a row per failed run', async () => {
    const fetchMock = stubFetch(jsonResponse([makeRun()]))

    renderWithProviders(<RunsPage />)

    const link = await screen.findByRole('link', { name: 'Sandbox failures #42' })
    expect(link).toHaveAttribute('href', '/runs/12')

    const row = link.closest('tr')!
    expect(within(row).getByText('3f2c9a1')).toBeInTheDocument()
    expect(within(row).getByText('Ingested')).toBeInTheDocument()
    expect(within(row).getByText('Failure')).toBeInTheDocument()
    expect(within(row).getByRole('link', { name: /on GitHub/ })).toHaveAttribute(
      'href',
      'https://github.com/Raju6302/ci-failure-assistant/actions/runs/123',
    )
    expect(fetchMock).toHaveBeenCalledWith('/api/runs?limit=50', expect.anything())
  })

  it('shows the failure category and summary when the run has been analyzed', async () => {
    stubFetch(
      jsonResponse([
        makeRun({ category: 'COMPILE_ERROR', summary: 'InvoiceService.java:9: cannot find symbol: method price()' }),
        makeRun({ id: 13, runNumber: 43 }),
      ]),
    )

    renderWithProviders(<RunsPage />)

    const analyzed = (await screen.findByRole('link', { name: 'Sandbox failures #42' })).closest('tr')!
    expect(within(analyzed).getByText('Compile error')).toBeInTheDocument()
    expect(within(analyzed).getByText('InvoiceService.java:9: cannot find symbol: method price()')).toHaveAttribute(
      'title',
      'InvoiceService.java:9: cannot find symbol: method price()',
    )

    const pending = screen.getByRole('link', { name: 'Sandbox failures #43' }).closest('tr')!
    expect(within(pending).getByText('—')).toBeInTheDocument()
  })

  it('shows the empty state when there are no runs', async () => {
    stubFetch(jsonResponse([]))

    renderWithProviders(<RunsPage />)

    expect(await screen.findByText('No failed runs yet')).toBeInTheDocument()
    expect(screen.getByText('docs/local-development.md')).toBeInTheDocument()
  })

  it('shows an error state and recovers on retry', async () => {
    const fetchMock = stubFetch(jsonResponse({ error: 'boom' }, 500), jsonResponse([makeRun()]))

    renderWithProviders(<RunsPage />)

    await userEvent.click(await screen.findByRole('button', { name: 'Retry' }))

    expect(await screen.findByRole('link', { name: 'Sandbox failures #42' })).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})
