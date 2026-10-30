import { BackLink } from '../components/BackLink'
import { EmptyState } from '../components/EmptyState'

export function NotFoundPage() {
  return (
    <section>
      <BackLink />
      <EmptyState title="Page not found" />
    </section>
  )
}
