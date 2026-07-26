# Protocol-v9 transport foundation

Issue #347 implements only the first production layer of the frozen
[protocol-v9 contract](netplay-protocol-v9.md). It is deliberately opt-in and is not connected to
Coffee GB's default netplay menu or `ConnectionController`; shipped user netplay remains the
isolated protocol-v8 path.

## Implemented boundary

The platform-neutral controller package `controller.network.v9` owns:

- explicit stable message, capability, error, limit, timeout, flag, role, mode, and lifecycle
  values whose wire identities never use enum ordinals;
- the fixed 64-byte big-endian `CGB9`/9.0 frame encoder and incremental decoder;
- exact HELLO payload validation and bidirectional required-capability negotiation;
- immutable lifecycle snapshots from connect/listen through the post-HELLO
  `AWAITING_PAIRING` boundary;
- injected monotonic deadlines, bounded reader/writer retention, cancellation, idempotent close,
  socket/task ownership, and an accept loop that isolates malformed or stalled candidates; and
- an EDT-marshalling Swing adapter whose controller-facing source has no Swing or AWT dependency.

The decoder retains one fixed header and at most one declared frame payload. It validates the
frozen decisive-byte precedence, per-message declaration, checked 64-byte frame overhead, and
session queue/decoded aggregate before allocation. A delivered frame owns its reservation until
closed. The queue ceilings remain 256 frames, 33,817,172 complete wire bytes, and 134,217,728
decoded bytes. Raw RFC-1951 DEFLATE is exact-length, no-dictionary, no-concatenation, and permitted
only for a registered bulk chunk after capability 10 is negotiated.

Each direction starts at sequence zero, accepts `0xfffffffe` as its last frame, and then closes;
`0xffffffff` is never emitted. The response ledger tracks exact request type, correlation, and
one-response ownership without retaining an ever-growing completed-response history. Every live
inbound foundation frame passes through that ledger before its payload handler. With no Phase-1
request outstanding, a response-form ERROR is therefore rejected as `CORRELATION_ERROR`. Before
the server HELLO, the client accepts only a normal HELLO or a non-response, sequence-zero terminal
`SERVER_BUSY`/`SERVER_FULL` rejection. Input fragmentation is arbitrary, coalesced frames are
accepted, EOF is typed as truncated or unexpected, and bytes after a terminal frame are rejected
as trailing data.

## HELLO-only lifecycle

The server sends HELLO first. Both roles validate protocol 9.0, sorted unique capability records,
all seven required version-1 capabilities, unknown-required rejection, and the optional
four-player gate. The connection then publishes `WAIT_AUTH` (server) or `SEND_AUTH` (client), both
classified as `AWAITING_PAIRING`, and does not continue.

AUTH, invitation proof, manifest, consent, ROM/battery transfer, checkpoints, playing messages,
diagnostics, discovery, and Mobile Adapter behavior are unavailable. Later message declarations
are rejected before payload allocation by the lifecycle policy. Phase #348 is the next gate.

## Ownership, errors, and privacy

The transport owns its channel, reader/writer tasks, deadline task, bounded queue, and retained
frames. Cancellation or timeout closes each resource idempotently and cannot block an emulator
thread or the Swing EDT. A failed candidate is closed independently; it does not terminate the
listener or another candidate.

A local peer-visible rejection first freezes queue and decoder admission and records its original
typed failure. The implementation then makes one best-effort terminal ERROR write, half-closes
output, and enters `TERMINAL_CLEANUP`. It drains only for peer EOF; it does not parse another
frame. Peer EOF closes promptly. Otherwise the injected monotonic cleanup deadline closes the
candidate at exactly 2,000 ms (it remains open at 1,999 ms) without replacing the rejection with
`TIMEOUT`. A failed error write also closes only that candidate while preserving the original
reason. Received terminal errors may close promptly.

Server ownership includes pending candidates and every handed-off `AWAITING_PAIRING` connection.
A close listener removes a handed-off connection from the registry when its later owner closes it.
Candidate construction, start, and callback failures close/remove the candidate and leave the
accept loop available. Server shutdown and callback delivery share one gate, so shutdown cannot
leave a post-close callback. Accepted-candidate admission shares a shutdown gate: an accept that
has not yet registered is closed immediately after shutdown begins, while `shutdownNow` return
values and the tracked pending set are both drained so no queued candidate is retained.

The cancellable client connector owns a channel/connection only until a successful started
connection is atomically handed to its callback. Cancellation is exactly once at every earlier
boundary; the injected monotonic connect deadline is exactly 5,000 ms. After handoff, closing the
attempt cannot cancel the connection. Schedulers created internally are closed with their owner;
an injected scheduler, including an injected system scheduler, remains caller-owned and reusable.

Public failures contain a stable error code and a fixed local diagnostic category. Raw peer
diagnostic text is strictly validated where required by the wire schema but is discarded rather
than surfaced. Exceptions, payload bytes, paths, ROM/save content, invitation material, and remote
strings are never used as UI diagnostics.

Invitation authentication and encryption are **not implemented**. The eventual protocol is
plaintext TCP and does not provide confidentiality or protection against an on-path attacker.
The foundation must not be presented as a secure or playable Internet netplay flow.

## Verification

`ProtocolV9ProductionTest` compares production identities and limits against every frozen registry
row and exercises fragmentation, coalescing, truncation, header precedence, exact/+1 limits,
checked aggregate admission, compressed corruption/bombs, sequence exhaustion, HELLO negotiation,
deadline/cancellation cleanup, partial writes, and accept-loop survival. The independent Phase-0
reference model and hostile vectors remain unchanged. `V9SwingLifecycleAdapterTest` proves EDT
delivery and the controller/Swing dependency boundary.
