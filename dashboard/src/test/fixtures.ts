import type { Diagnosis, FailedJob, FailureAnalysis, WorkflowRun } from '../api/runs'

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
    diagnosis: null,
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

export function makeDiagnosis(overrides: Partial<Diagnosis> = {}): Diagnosis {
  return {
    source: 'AGENT',
    category: 'TEST_FAILURE',
    rootCause: 'PriceCalculator.applyDiscount multiplies by (1 - percent) instead of (1 - percent / 100).',
    confidence: 'HIGH',
    evidence: [
      {
        source: 'file',
        path: 'sandbox-repos/java-test-failure/src/main/java/shop/PriceCalculator.java',
        line: 7,
        quote: 'return price * (1 - percent);',
      },
      { source: 'log', path: '', line: 208, quote: 'expected: <90.0> but was: <-900.0>' },
    ],
    suggestedFix: 'Divide the percentage by 100 before subtracting it from 1.',
    patch: [
      '--- a/src/main/java/shop/PriceCalculator.java',
      '+++ b/src/main/java/shop/PriceCalculator.java',
      '@@ -7 +7 @@',
      '-        return price * (1 - percent);',
      '+        return price * (1 - percent / 100);',
    ].join('\n'),
    model: 'claude-opus-5-5',
    steps: 3,
    inputTokens: 12000,
    outputTokens: 900,
    costUsd: 0.066,
    latencyMs: 41000,
    agentError: null,
    createdAt: '2026-10-30T10:01:10Z',
    ...overrides,
  }
}
