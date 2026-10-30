# Sandbox projects

Small projects that **fail on purpose**, so the assistant always has real, reproducible
CI failures to work on. Each folder covers one kind of failure: compile error, failing
test, missing dependency, flaky test, broken Docker build and a job timeout.

Run them from GitHub: **Actions → Sandbox failures → Run workflow**, then pick one
scenario or `all`. Each scenario runs as its own job in
[`.github/workflows/sandbox-failures.yml`](../.github/workflows/sandbox-failures.yml),
and the failed run is sent to the assistant through the webhook like any other run.

- [`scenarios.json`](scenarios.json) says how to run each project (toolchain and command).
  The workflow reads its job list from this file.
- The expected diagnosis for each scenario lives in
  [`evals/sandbox-expected.json`](../evals/sandbox-expected.json). It's kept out of
  these folders, and the agent's code tools must exclude `evals/`, so the answers can't
  leak into a diagnosis.

To add a scenario, create a folder, add it to `scenarios.json`, and add its expected
diagnosis to `evals/sandbox-expected.json`.
