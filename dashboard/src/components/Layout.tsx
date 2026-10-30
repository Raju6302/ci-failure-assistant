import { Link, Outlet } from 'react-router'
import { GitHubIcon } from './icons'

const REPO_URL = 'https://github.com/Raju6302/ci-failure-assistant'

export function Layout() {
  return (
    <div className="flex min-h-dvh flex-col">
      <header className="border-b border-zinc-200 bg-white/80 backdrop-blur dark:border-zinc-800 dark:bg-zinc-950/80">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-3 sm:px-6">
          <Link to="/" className="min-w-0">
            <span className="block text-sm font-semibold tracking-tight">CI Failure Assistant</span>
            <span className="block truncate text-xs text-zinc-500 dark:text-zinc-400">
              Diagnoses failed GitHub Actions builds
            </span>
          </Link>
          <a
            href={REPO_URL}
            target="_blank"
            rel="noreferrer"
            className="flex shrink-0 items-center gap-2 rounded-md px-2 py-1.5 text-sm text-zinc-600 hover:bg-zinc-100 hover:text-zinc-900 dark:text-zinc-400 dark:hover:bg-zinc-900 dark:hover:text-zinc-100"
          >
            <GitHubIcon className="size-4" />
            <span className="hidden sm:inline">GitHub</span>
          </a>
        </div>
      </header>
      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-6 sm:px-6 sm:py-8">
        <Outlet />
      </main>
    </div>
  )
}
