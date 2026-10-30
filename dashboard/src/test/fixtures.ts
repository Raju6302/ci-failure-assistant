import type { FailedJob, FailureAnalysis, WorkflowRun } from '../api/runs'

export function makeRun(overrides: Partial<WorkflowRun> = {}): WorkflowRun {
  return {
    id: 12,
    repository: 'Raju6302/ci-failure-assistant',
    workflowName: 'Sandbox failures',
    branch: 'main',
    headSha: '3f2c9a1b7e4d5c6b8a9f0e1d2c3b4a5f6e7d8c9b',
    runNumber: 42,
    runAttempt: 1,
    event: 'workflow_dispatch',
    conclusion: 'failure',
    status: 'INGESTED',
    failedJobCount: 1,
    htmlUrl: 'https://github.com/Raju6302/ci-failure-assistant/actions/runs/123',
    receivedAt: '2026-10-30T10:00:00Z',
    ingestedAt: '2026-10-30T10:00:04Z',
    errorMessage: null,
    category: null,
    summary: null,
    ...overrides,
  }
}

export function makeJob(overrides: Partial<FailedJob> = {}): FailedJob {
  return {
    id: 7,
    githubJobId: 987654321,
    name: 'java-test-failure',
    conclusion: 'failure',
    failedSteps: ['Run tests'],
    logBytes: 48213,
    logTruncated: false,
    htmlUrl: 'https://github.com/Raju6302/ci-failure-assistant/actions/runs/123/job/987654321',
    startedAt: '2026-10-30T09:59:10Z',
    completedAt: '2026-10-30T09:59:58Z',
    analysis: null,
    ...overrides,
  }
}

export function makeAnalysis(overrides: Partial<FailureAnalysis> = {}): FailureAnalysis {
  return {
    category: 'TEST_FAILURE',
    failedStep: 'Build and test',
    summary: 'PriceCalculatorTest.appliesPercentageDiscount: expected <90.0> but was <-900.0>',
    excerpt: [
      '[ERROR] Failures: ',
      '[ERROR]   PriceCalculatorTest.appliesPercentageDiscount:13 expected: <90.0> but was: <-900.0>',
      '',
      '[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0',
    ].join('\n'),
    excerptStartLine: 208,
    excerptEndLine: 211,
    codeReferences: [
      { path: 'sandbox-repos/java-test-failure/src/test/java/shop/PriceCalculatorTest.java', line: 13 },
      { path: 'pom.xml', line: null },
    ],
    fingerprint: 'c0c516b8f073'.padEnd(64, '0'),
    ...overrides,
  }
}

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
