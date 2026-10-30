# Dashboard

Web UI for CI Failure Assistant. It lists failed GitHub Actions runs picked up by the
`api-service` and shows each run's failed jobs, failed steps and log sizes. AI diagnoses
will appear here from Phase 4 of the [roadmap](../ROADMAP.md).

Built with React, TypeScript, Vite, Tailwind CSS v4, TanStack Query and React Router.

## Running locally

Requires Node 24.

```sh
npm install
npm run dev
```

The dev server runs on http://localhost:5173 and proxies `/api` and `/actuator` to the
`api-service` on http://localhost:8080, so start that first. Without it, the dashboard
shows an error state with a retry button.

## Scripts

| Script            | What it does                                     |
| ----------------- | ------------------------------------------------ |
| `npm run dev`     | Start the Vite dev server with hot reload        |
| `npm run build`   | Type-check and build the production bundle       |
| `npm run lint`    | Lint with oxlint (warnings fail the run)         |
| `npm test`        | Run the Vitest suite once                        |
| `npm run preview` | Serve the production build locally, same proxy   |

## Docker

`Dockerfile` builds the bundle and serves it with nginx on port 80. nginx proxies `/api/`
to `http://api-service:8080`, the service name used in Docker Compose.
