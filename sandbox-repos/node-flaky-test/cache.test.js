import { test } from 'node:test';
import assert from 'node:assert/strict';

function warmCache() {
  return new Promise((resolve) => setTimeout(() => resolve({ ready: true }), Math.random() * 100));
}

test('cache is warm after 50ms', async () => {
  let state = { ready: false };
  warmCache().then((result) => { state = result; });
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.equal(state.ready, true);
});
