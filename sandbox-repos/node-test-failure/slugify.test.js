import { test } from 'node:test';
import assert from 'node:assert/strict';
import { slugify } from './slugify.js';

test('lowercases and joins words with dashes', () => {
  assert.equal(slugify('Hello World'), 'hello-world');
});

test('collapses punctuation between words', () => {
  assert.equal(slugify('CI is broken, again!'), 'ci-is-broken-again');
});
