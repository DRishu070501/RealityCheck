import { screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { FactorySummary } from '@/features/overview/FactorySummary'
import { demoRoutes, emptyPage, mockBackend } from '@/test/backend'
import { jsonResponse, renderWithProviders } from '@/test/utils'

afterEach(() => {
  vi.unstubAllGlobals()
})

function figure(label: string): HTMLElement {
  const container = screen.getByText(label, { selector: 'dt' }).parentElement
  if (!container) throw new Error(`No figure labelled ${label}`)
  return container
}

describe('FactorySummary', () => {
  it('summarises the configured factory from backend data, with links to the detail pages', async () => {
    mockBackend(demoRoutes())

    renderWithProviders(<FactorySummary />)

    expect(await screen.findByRole('heading', { name: 'Surat Diamond Works' })).toBeInTheDocument()
    expect(screen.getByText('Synthetic demo data')).toBeInTheDocument()
    expect(screen.getByText('Configuration')).toBeInTheDocument()

    expect(await within(figure('Workflow stages')).findByText('8')).toBeInTheDocument()
    expect(await within(figure('Resource pools')).findByText('14')).toBeInTheDocument()
    expect(within(figure('Machine units')).getByText('21')).toBeInTheDocument()
    expect(within(figure('Employee units')).getByText('31')).toBeInTheDocument()
    expect(within(figure('Working time per week')).getByText('48 h')).toBeInTheDocument()

    expect(within(figure('Workflow stages')).getByRole('link')).toHaveAttribute('href', '/workflow')
    expect(within(figure('Resource pools')).getByRole('link')).toHaveAttribute('href', '/resources')
    expect(within(figure('Working time per week')).getByRole('link')).toHaveAttribute('href', '/factory')
  })

  it('explains how to load a factory when there is none', async () => {
    mockBackend({ 'GET /api/factories': () => jsonResponse(emptyPage) })

    renderWithProviders(<FactorySummary />)

    expect(await screen.findByText('No factory is configured yet')).toBeInTheDocument()
  })
})
