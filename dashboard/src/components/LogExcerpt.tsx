interface LogExcerptProps {
  text: string
  /** Line number of the first line in the job log; without it, no gutter is shown. */
  startLine: number | null
}

/**
 * Log lines in a monospace block that scrolls sideways instead of wrapping, so stack traces
 * keep their shape. The gutter shows the original log line numbers.
 */
export function LogExcerpt({ text, startLine }: LogExcerptProps) {
  const lines = text.split('\n')
  return (
    <section
      aria-label={startLine === null ? 'Error excerpt' : `Error excerpt, log lines ${startLine} to ${startLine + lines.length - 1}`}
      // Scrollable content must be reachable by keyboard, or long lines can't be read without a mouse.
      // oxlint-disable-next-line jsx-a11y/no-noninteractive-tabindex
      tabIndex={0}
      className="overflow-x-auto rounded-md border border-zinc-200 bg-zinc-50 focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:outline-none dark:border-zinc-800 dark:bg-zinc-950"
    >
      <pre className="py-2 font-mono text-xs leading-5">
        <code className="grid w-max min-w-full grid-cols-[auto_1fr]">
          {lines.map((line, index) => (
            // Lines can repeat, so the index is part of the key.
            <span key={index} className="contents">
              {startLine === null ? (
                <span aria-hidden="true" />
              ) : (
                <span
                  aria-hidden="true"
                  className="pr-3 pl-3 text-right text-zinc-400 tabular-nums select-none dark:text-zinc-500"
                >
                  {startLine + index}
                </span>
              )}
              <span className="pr-4 pl-1 whitespace-pre text-zinc-800 dark:text-zinc-200">{line || ' '}</span>
            </span>
          ))}
        </code>
      </pre>
    </section>
  )
}
