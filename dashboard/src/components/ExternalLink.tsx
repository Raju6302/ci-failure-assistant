import type { ReactNode } from 'react'
import { ExternalIcon } from './icons'

interface ExternalLinkProps {
  href: string
  children: ReactNode
  className?: string
}

/** Opens in a new tab; used for every link out to GitHub. */
export function ExternalLink({ href, children, className = '' }: ExternalLinkProps) {
  return (
    <a
      href={href}
      target="_blank"
      rel="noreferrer"
      className={`inline-flex items-center gap-1 text-zinc-600 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-100 ${className}`}
    >
      {children}
      <ExternalIcon className="size-3.5 shrink-0" />
    </a>
  )
}
