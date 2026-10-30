import type { RouteObject } from 'react-router'
import { Layout } from './components/Layout'
import { NotFoundPage } from './pages/NotFoundPage'
import { RunDetailPage } from './pages/RunDetailPage'
import { RunsPage } from './pages/RunsPage'

export const routes: RouteObject[] = [
  {
    element: <Layout />,
    children: [
      { index: true, element: <RunsPage /> },
      { path: 'runs/:id', element: <RunDetailPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]
