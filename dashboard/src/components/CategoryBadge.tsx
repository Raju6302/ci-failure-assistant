import type { FailureCategory } from '../api/runs'
import { categoryAppearance } from '../lib/category'

export function CategoryBadge({ category }: { category: FailureCategory }) {
  const { label, className } = categoryAppearance(category)
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap ring-1 ring-inset ${className}`}
    >
      <span className="sr-only">Category: </span>
      {label}
    </span>
  )
}
