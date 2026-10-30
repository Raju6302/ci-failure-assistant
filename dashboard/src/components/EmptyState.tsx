import type { ReactNode } from 'react'
import { Card } from './Card'

interface EmptyStateProps {
  title: string
  children?: ReactNode
}

export function EmptyState({ title, children }: EmptyStateProps) {
  return (
    <Card className="px-6 py-12 text-center">
      <p className="font-medium">{title}</p>
      {children && <div className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">{children}</div>}
    </Card>
  )
}
