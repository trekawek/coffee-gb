# ADR 0002: clean-room Mobile Adapter architecture

- Status: accepted for the Phase 0 contract gate
- Date: 2026-07-26
- Issues: #346, #318, #311

## Decision

Mobile Adapter GB is an exclusive, platform-neutral **serial peripheral**. It is never a rollback
`LinkMode`. Its future dependency direction is strictly:

1. `core`: deterministic byte/bit engine, injected emulated clock, bounded parser/buffers, immutable
   capture state, and a nonblocking request/result port;
2. `controller`: bounded asynchronous backend jobs, DNS/network/filesystem policy, cancellation,
   status/error events, safe-point configuration, and a deterministic no-socket fake backend that
   implements the same bounded request/result port for tests; and
3. `swing`: settings, consent, status, and error presentation.

Core may not import or own sockets, DNS, files, threads, futures, executors, AWT/Swing, host wall
clock, or blocking callbacks. Controller and desktop work may never block the emulator thread or
EDT. The engine's clock and timeout input is explicit and deterministic. All queues, payloads,
connection slots, and configuration ranges have the limits in
[mobile-adapter-contract.md](../mobile-adapter-contract.md).

## Ownership and state

Exactly one serial peripheral owns a Game Boy serial endpoint. Changing the configured peripheral
is prepared off-thread and committed atomically at the emulator safe point; replacement cancels
old jobs and releases their resources before the new endpoint becomes visible. Captured state may
contain packet/parser phase, bounded request/response bytes, configuration bytes, deterministic
timer counters, connection-slot identifiers, and status codes. It must never contain a live
socket, DNS resolver, file handle, callback, task, thread, executor, UI object, or host timestamp.

Save/load and rewind restore deterministic engine state but disconnect/cancel every live backend
operation. A restored request may be reported as disconnected/cancelled and explicitly retried by
guest software; it may not resurrect a host connection. Failed restore/configuration leaves both
the old endpoint and backend ownership unchanged.

## Clean-room boundary

The evidence inventory in `core/src/test/resources/mobile-adapter/sources.tsv` is the only input to
the Phase 0 byte contract. Facts are independently restated; no third-party implementation source
is copied or translated. Dan Docs is the primary public reverse-engineering document. LGPL
`libmobile` is used only to corroborate the `99 66` magic, command numbers, and bounded parser
shape. Later contributors must not consult a source under an incompatible license while writing
production code unless a separate legal review records a safe method.

No Nintendo server endpoint, credential, configuration image, commercial ROM/save, proprietary
trace, or firmware byte is committed. The future backend targets explicit user-configured local
or test services only. This ADR does not implement inbound/relay operation, Nintendo-service
impersonation, TLS, GBA/NDS serial modes, or general Internet exposure.
