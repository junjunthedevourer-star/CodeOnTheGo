# ChatGPT ↔ Code on the Go connector — Phase 2 foundation

This is an additive, isolated integration in the CoGo 2 feature branch. It does **not** modify CoGo's Android runtime, application ID, plugin loader, native toolchain, AI Core, or existing editor/build implementation.

## Target architecture

ChatGPT (eligible MCP client) → authenticated HTTPS `/mcp` relay → authenticated outbound WSS from phone → AI Development Bridge plugin `com.aidevbridge.cogo` → **existing Phase 1 validated tool executor** → currently open CoGo project.

The relay must remain a transport and policy layer. Never implement project filesystem, Gradle, rollback, or dirty-editor synchronization in it. The phone independently enforces project confinement, stale SHA, local approvals, and audit even after relay authorization. The mobile app must not expose an unauthenticated listener.

## What is in this commit

`phase2-relay/` contains an executable transport-independent protocol contract, relay-side scope and feature gates, a single-device authenticated session/request hub, and Node built-in unit tests. The hub handles outbound transport **callbacks** only; it is not yet a WebSocket server, a ChatGPT MCP server, an OAuth verifier, or an Android plugin. Do not deploy it as an internet service. No secrets or source files are recorded.

Implemented safety behaviors: exact 22-tool allowlist; protocol v1 validation; UUIDs; TTL; 64 KiB argument / 1 MiB result cap; unsafe JSON-property rejection; relay OFF by default; independent scope/category checks; local-approval flag; device token digest verification with constant-time comparison; hello capability verification; one active device; no offline request queue; in-memory duplicate-request suppression; session binding; explicit cancellation; bounded pending requests. Sensitive-file and project path validation remain authoritative on the phone. A timeout/cancellation/disconnect cannot prove an in-flight phone mutation did not run: errors explicitly say outcome may be unknown.

## Test locally

Requires Node.js 20 or later (no npm dependencies):

```bash
cd integrations/chatgpt-cogo/phase2-relay
npm test
```

## Integration gates — NOT yet implemented

1. Locate and inspect the **current actual Phase 1 AI Development Bridge source** and its accepted tests. It is not in the accessible CodeOnTheGo fork or currently located in the file Library. No guessed package/class/IDE service signatures are allowed.
2. Integrate phone-facing WSS client with Phase 1 shared engine, Android Keystore, explicit per-action local approvals, project confinement, persistence, foreground/lifecycle behavior based on verified plugin API. Do not alter AI Core.
3. Implement relay HTTPS Streamable MCP using official MCP SDK, validated OAuth/OIDC JWT + protected-resource metadata, TLS, rate limits, request audit, bounded jobs and cancellation; call `DeviceHub` only with trusted verified principals. Replace in-memory-only mutation dedup with recoverable journal semantics, and implement authenticated credential rotation/revocation.
4. Deploy only with externally verified source backup and production authentication; test with MCP Inspector against a disposable CoGo project, then test ChatGPT only in an eligible workspace and on a supported client.
5. Build the Android `.cgp` only from a marked disposable build copy, never the sole authoritative source. Do not run destructive cleanup in the source workspace.

## Critical limits

The `DeviceHub.connect()` callback trusts the *caller* to have accepted the WebSocket upgrade; its SHA-256 verifier checks a randomly generated, high-entropy device secret, not OAuth. `DeviceHub.dispatch()` trusts its host application to construct `principal` from a verified token; user/model-provided subject/scopes must never be passed directly. `hello()` only validates announced capability names; the phone's actual executor must validate again. `requiresLocalApproval` is informational for relay routing and is never sufficient proof of phone approval. No end-to-end mobile/ChatGPT connectivity is claimed.
