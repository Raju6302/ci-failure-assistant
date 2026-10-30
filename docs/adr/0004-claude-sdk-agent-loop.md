# 4. Call Claude through the official SDK with our own tool-use loop

**Status:** accepted (Phase 4)

## Context

The roadmap first planned Spring AI's `ChatClient` for the agent. The diagnosis loop
needs a few things a generic abstraction makes hard: a hard step limit and a token
budget, exact token and cost accounting per call, Claude-specific request options
(effort, server-side refusal fallback, prompt caching), and tools discovered at runtime
from an MCP server.

## Decision

- Use the **official Anthropic Java SDK** (`com.anthropic:anthropic-java`) on the beta
  Messages endpoint, model `claude-opus-5-5` at `high` effort (both configurable).
- Write the tool-use loop ourselves (`DiagnosisAgent`): list tools from MCP, call the
  model, run the requested tools, send all results of a turn back in one message, and
  repeat until the model calls `submit_diagnosis`, which is a strict tool whose
  arguments are also validated before use.
- Keep the conversation **append-only**: each response is sent back unchanged
  (`toParam()`), so the model's thinking blocks stay valid and the cached prefix (tools +
  system prompt) is reused on every call.
- Opt in to **server-side fallback** (`fallbacks: "default"`), and treat a `refusal` stop
  reason as a permanent error that falls back to the rule-based diagnosis.
- Spring AI is still used where it fits: the MCP server, and local embeddings for RAG.

## Consequences

- Tests run the real SDK against a scripted fake of the Messages API (WireMock), so the
  exact JSON we send is checked.
- New Claude features (e.g. task budgets) can be adopted without waiting for an
  abstraction to support them.
- We own the loop's edge cases (max_tokens, text answers without a tool call, invalid
  submissions); each has a test.
