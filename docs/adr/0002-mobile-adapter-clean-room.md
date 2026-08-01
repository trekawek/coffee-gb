# ADR 0002: clean-room Mobile Adapter architecture

- Status: accepted; Phase 0 contract through Phase 3 wire/service path implemented; ROM-to-service validation remains partial
- Date: 2026-07-30
- Updated: 2026-08-01
- Issues: #399, #353, #352, #351, #346, #314, #318, #311

## Decision

Mobile Adapter GB is an exclusive, platform-neutral **serial peripheral**. It is never a rollback
`LinkMode`. Its dependency direction is strictly:

1. `core`: deterministic byte/bit engine, injected emulated clock, bounded parser/buffers, immutable
   capture state, and a nonblocking request/result port;
2. `controller`: bounded asynchronous backend jobs, DNS/network/filesystem policy, cancellation,
   status/error events, safe-point configuration, and a deterministic no-socket fake backend that
   implements the same bounded request/result port for tests; and
3. `swing`: settings, consent, status, and error presentation.

Core may not import or own sockets, DNS, files, threads, futures, executors, AWT/Swing, host wall
clock, or blocking callbacks. Controller and desktop work may never block the emulator thread or
EDT. Phase 1 realized this dependency direction with `MobileAdapterEngine`,
`MobileAdapterSerialEndpoint`, a bounded nonblocking backend port, and a deterministic in-memory
fake. Phase 2 adds generation-bearing typed completions and a one-outstanding-request direct
response channel for the documented DNS/TCP/UDP commands. Phase 3 adds the byte-pipelined
acknowledgement/response schedule and a bounded blue-adapter dial/status/ISP lifecycle modeled for
Japanese Pokémon Crystal. Only wake, BEGIN, telephone status, and END are confirmed by the current
real-ROM probe; later service commands are exercised synthetically. The engine's clock and timeout
input is explicit and deterministic. All
queues, payloads, connection slots, and configuration ranges have
the limits in
[mobile-adapter-contract.md](../mobile-adapter-contract.md).
The in-memory fake uses one immutable snapshot and a lock-free atomic compare/exchange solely to
linearize bounded generation cancellation. That primitive creates no thread, task, future,
executor, callback, or blocking wait and is not captured as emulator state.

## Ownership and state

Exactly one serial peripheral owns a Game Boy serial endpoint. Changing the configured peripheral
prepares a candidate before the handoff and commits the core installation atomically at the
emulator safe point. Only after that commit succeeds does the controller disconnect the previous
endpoint and cancel its jobs. A failed installation disconnects the candidate and leaves the
previous endpoint and its backend ownership usable.

Persisted custom-server policy is not runtime authority. Session consent and the private/local
development gate are process-local. Editing either gate or the policy directly and monotonically
revokes every prepared backend under the coordinator authority lock before requesting an endpoint
refresh through event delivery. First revocation rotates the generation; an already-revoked backend
stays unavailable, and the owner loop closes its DNS capabilities and sockets. The event bus is
therefore presentation and handoff, not the fail-closed authorization boundary. Explicit
configuration persistence has one active write and one queued write. Changed guest writes use a
separate replaceable latest-image slot on that same writer, become the in-memory endpoint source
immediately, and are merged with the latest durable device ID and policy. Failure retains the
acknowledged dirty image without an automatic retry loop. The controller drains it at frame and
ownership boundaries, and final close includes a bounded retry in its persistence barrier. Shutdown
and a retry each use their own 2,000 ms deadline shared across the writer and all still-tracked
backends.

The desktop exposes its retained Mobile Adapter configuration dialog and bounded owner-selected
image import from the **Peripherals** menu. Its controller reader runs off the EDT, rejects a
symbolic link in the selected final path component, and accepts only a stable regular
exact 256-byte opaque image or a validated exact 512-byte `MA`/`LM` envelope. It requires a
non-null provider file key, compares type, identity, size, and timestamps around the open/read, and
compares the opened channel size at both checkpoints before discarding all library metadata after
bytes `0..255`. Coffee GB does not chmod or modify the selected source, create a file-level copy,
or log or persist the selected source path; the path is retained only while the queued import runs.
The private store target, its same-file aliases, and names reserved for atomic backup/temporary
artifacts are rejected before read and rechecked before save, so an observed conflict fails before
persistence starts. With a stable directory entry, persistence neither replaces nor cleans up the
selected source. Only the copied or extracted 256 bytes enter the existing owner-only atomic
configuration store. The durable Coffee GB device ID and structured custom-server policy are
preserved.

Java 16 exposes no portable descriptor-bound file type/identity query or nonblocking regular-file
open. A process able to rewrite the selected directory can therefore still swap an entry between
the identity checkpoints or substitute a FIFO during the narrow blocking-open window. Providers
without a stable file key fail closed with `IMPORT_READ_FAILED`; owners should select private input
from a directory not writable by an untrusted process.

Every owner-triggered import attempt revokes all prepared/active backends and both process-local
gates before reporting success or any stale, malformed, conflicting-source, busy, read, or storage
failure. No `LM` device, DNS, relay, token, or host value becomes Coffee GB network policy or
authority. An import therefore never authorizes networking; the owner must separately grant both
applicable runtime gates again.

Captured state may contain packet/parser phase, bounded request/response bytes, configuration
bytes, deterministic timer counters, the bounded pending-packet count, status codes, and a boolean
marker that external I/O existed at capture. Runtime connection identifiers, backend generations,
and request IDs are deliberately excluded. Captured state must never contain a live socket, DNS
resolver, file handle, callback, task, thread, executor, UI object, or host timestamp.

The private 256-byte adapter configuration is deterministic emulator state and is therefore stored
in save states. A capture taken while an ISP-login packet is only partly received can also contain
the bounded ID/password bytes already present in the parser buffer. Completed login immediately
clears that parser data, and Coffee GB never authenticates, logs, or forwards it, but state files
must still be treated as private and excluded from diagnostics or sharing unless explicitly
redacted.

Capture/save observes only deterministic engine state and does not disconnect live backend work.
If a request or logical connection is live, the captured image removes backend-reserved output and
records the stable external-I/O-disconnected outcome plus marker. State load/restore and rewind
disconnect/cancel every live backend operation before restoring that state. A restored request is
therefore reported as disconnected and may be explicitly retried by guest software; it may not
resurrect a host connection. If capture intersects an active serial byte, the additive wire state
retains only that byte's already-latched reply and aborts the normalized transaction at its
boundary. Rolling rewind history is cleared whenever external ownership is
observed and starts fresh only after that ownership ends, so it cannot join pre-I/O and post-I/O
captures across omitted host effects. Failed restore/configuration leaves both
the old endpoint and backend ownership unchanged when validation or preparation rejects the
candidate before commit. Once a validated restore begins, backend cancellation is deliberately
irreversible: an unexpected apply failure rolls deterministic machine/endpoint state back but
leaves host backend work disconnected rather than attempting to resurrect it.

## Clean-room boundary

The protocol-evidence rows in `core/src/test/resources/mobile-adapter/sources.tsv` are the only
inputs to the Phase 0 byte contract. The REON server row records validation-target provenance only
and is not protocol authority. Facts are independently restated; no third-party implementation source
is copied or translated. Dan Docs is the primary public reverse-engineering document. LGPL
`libmobile` is used only to corroborate the `99 66` magic, command numbers, bounded parser shape,
the skipped-idle/validated-`4b` response gate, the `f0`/`f1`/`f2` retransmission categories, and
independently restated blue-adapter status/ISP response facts. Coffee GB adds its own four-retry
bound. No implementation code, data structure, control-flow translation, or source text is copied.
Later contributors must not consult a source under an incompatible license while writing
production code unless a separate legal review records a safe method.

The image-import format has a separate, narrow provenance boundary. Pinned MIT-licensed REON commit
[`f7bfc0470aed561936b396ed29f2bde50ca601ab`, `web/htdocs/user/adapter_config.php`](https://github.com/REONTeam/reon/blob/f7bfc0470aed561936b396ed29f2bde50ca601ab/web/htdocs/user/adapter_config.php)
is the public 512-byte producer used for the validation scenario. Pinned LGPL-3.0-or-later libmobile
commit
[`0704f56902f23b7ebf05c82c222e0e145e3140b6`, `config.c`](https://github.com/REONTeam/libmobile/blob/0704f56902f23b7ebf05c82c222e0e145e3140b6/config.c)
corroborates the game-visible and library-region checks. Coffee GB uses independently restated
magic, checksum, version, type, and size facts only; it copies no copyrighted implementation text,
data structure, or control flow.

No Nintendo server endpoint, credential, configuration image, commercial ROM/save, proprietary
trace, or firmware byte is committed. The backend targets explicit user-configured custom or
in-process test services only and is guarded by controller destination policy. Custom mode remains
an out-of-band network-authorization prerequisite even though Phase 3 models the guest's dial/login
commands. Coffee GB ships and automatically selects no Nintendo endpoint. Address-range policy
cannot determine the operator of
an arbitrary public IPv4 address, so the user remains responsible for selecting an intended custom
service that is not a Nintendo production endpoint. Raw DNS exposes its query name and
source/network metadata to the chosen resolver; TCP/UDP exposes source/network metadata and
plaintext guest payload to the chosen service and intervening network. This ADR does not implement
inbound/relay operation, Nintendo-service impersonation, TLS, GBA/NDS serial modes, or general
Internet exposure.
