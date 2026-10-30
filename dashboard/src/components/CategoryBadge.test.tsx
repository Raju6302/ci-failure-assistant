import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { FailureCategory } from '../api/runs'
import { categoryAppearance } from '../lib/category'
import { CategoryBadge } from './CategoryBadge'

const CATEGORIES: [FailureCategory, string][] = [
  ['COMPILE_ERROR', 'Compile error'],
  ['TEST_FAILURE', 'Test failure'],
  ['DEPENDENCY', 'Dependency'],
  ['FLAKY_TEST', 'Flaky test'],
  ['INFRASTRUCTURE', 'Infrastructure'],
  ['CONFIG_ERROR', 'Config error'],
  ['UNKNOWN', 'Unknown'],
]

describe('categoryAppearance', () => {
  it.each(CATEGORIES)('labels %s as "%s"', (category, label) => {
    expect(categoryAppearance(category).label).toBe(label)
  })

  it('gives every category its own colors, in light and dark mode', () => {
    const classes = CATEGORIES.map(([category]) => categoryAppearance(category).className)
    expect(new Set(classes).size).toBe(CATEGORIES.length)
    for (const className of classes) {
      expect(className).toMatch(/(^| )bg-\S+/)
      expect(className).toMatch(/(^| )dark:bg-\S+/)
      expect(className).toMatch(/(^| )dark:text-\S+/)
    }
  })
})

describe('CategoryBadge', () => {
  it('renders the label and names it as a category for screen readers', () => {
    render(<CategoryBadge category="INFRASTRUCTURE" />)

    const badge = screen.getByText('Infrastructure')
    expect(badge).toHaveClass('bg-sky-50')
    expect(badge).toHaveTextContent('Category: Infrastructure')
  })
})
