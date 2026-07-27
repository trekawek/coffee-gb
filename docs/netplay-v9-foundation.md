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
- immutable lifecycle snapshots from connect/listen through either the post-HELLO
  `AWAITING_PAIRING` boundary or, when the explicit Part-1 invitation owner is supplied, the
  post-AUTH `SEND_SERVER_MANIFEST`/`WAIT_SERVER_MANIFEST` boundary;
- an opt-in Part-2 MANIFEST-v1 codec and semantic exchange that accepts only caller-prepared,
  deeply owned metadata and stops at `SYNCHRONIZING` or `EXCHANGE_CONSENT`;
- an additional opt-in Part-3 plan with exact item-scoped CONSENT, lazy ROM/battery sources,
  bounded streaming, atomic verified candidate delivery, and an immutable pre-START preparation
  boundary;
- an additional opt-in #349 play plan with direct StateFile-v2 checkpoints, two-stage target
  prepare/commit, START/READY, bounded ACTIVE input/control, and normal/four-player barriers;
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

## HELLO and invitation-auth lifecycle

The server sends HELLO first. Both roles validate protocol 9.0, sorted unique capability records,
all seven required version-1 capabilities, unknown-required rejection, and the optional
four-player gate. A foundation connection with no invitation owner still publishes `WAIT_AUTH`
(server) or `SEND_AUTH` (client), both classified as `AWAITING_PAIRING`, and does not continue.
This preserves the opt-in #347 foundation API.

Part 1 of #348 adds the exact canonical invitation parser/renderer, 16-byte `SecureRandom`
generation, monotonic expiry/rate limiting, one-use proof ownership, exact AUTH/AUTH_RESULT
payloads, and atomic slot reservation. When the server owns `V9InvitationHost` and the client owns
`V9ClientInvitation`, successful AUTH publishes an immutable boundary for authenticated slot
`1` (normal) or `1..3` (four-player). The next legal frozen state is
`SEND_SERVER_MANIFEST`/`WAIT_SERVER_MANIFEST`.
Invitation values, the host ledger, and client proof owners share one wipeable token buffer through
explicit leases rather than retaining independent token copies. Transfer to client authentication
invalidates the source value. Use, expiry, replacement, host stop, or explicit invitation close
invalidates every shared lease and zeroes the buffer; releasing the final client-only lease also
zeroes it.

Part 2 is an explicit extension: both endpoints must supply immutable, already-prepared
`V9ManifestPlan` metadata before the connection starts. Transport code never opens a ROM, save,
StateFile, or path and never hashes content on its reader/writer task. The server sends first; each
side validates exact mode, authenticated guest, wire sender, full roster, generation, commitment,
required capabilities, entries, differences, and proposals before comparison. Normal is exactly
players 0/1. Four-player entries are exactly players 0..3 and every manifest in one prepared host
plan shares one generation and commitment, while each proposal remains confined to host 0 and
that authenticated guest.

An exact own-ROM pair with no proposals publishes an immutable `SYNCHRONIZING` boundary. A
well-formed warning proposal publishes `EXCHANGE_CONSENT`; this is only metadata awaiting consent
and authorizes nothing. Both boundary variants contain detached manifests, sanitized difference
and proposal values, and SHA-256 identities of the exact two manifest payloads. Missing/different
ROM data without its exact advanced proposal rejects; no mismatch triggers a transfer.

Part 3 is another explicit extension. `V9Part3Plan` contains only caller-owned lazy providers,
an atomic completed-candidate sink, and bounded policy values. Each proposal remains in
`EXCHANGE_CONSENT` until its source and target have each sent one exact 116-byte approval bound to
the two manifest payload hashes, item identity, direction, class, asset, size, and digest. A
duplicate, replay, mismatch, extra vote, rejection, cancellation, or deadline failure closes only
that candidate. Zero proposals still complete directly.

Only after all approvals does the source task open a provider away from the emulator thread and
EDT. ROM and battery use the frozen 52-byte BEGIN, 8-byte-plus-data CHUNK, and 36-byte END schemas.
The implementation permits one open transaction per session, contiguous offsets, 65,536 decoded
bytes per chunk, at most four admitted chunk writes, 64 MiB ROMs, 2 MiB batteries, the existing
33,817,172-byte wire queue and 128 MiB decoded aggregate, a 120,000 ms synchronization deadline,
and a 15,000 ms progress deadline reset only by a validated complete frame. Raw RFC-1951 chunks
require capability 10. The source length and streaming SHA-256 must equal the approved proposal.
The receiver retains at most the approved class limit and gives its caller one detached candidate
only after exact END length and digest verification; failure or close wipes queued frames, source
buffers, and partial receiver retention where practicable.

Completion publishes sanitized per-item progress and an immutable `SYNCHRONIZING`
preparation-complete boundary. It does not send START. `V9SwingPart3Adapter` marshals only that
sanitized progress and explicit approve/reject/cancel actions across the EDT; private candidates
never enter Swing. Replacing an observer, cancelling, or closing suppresses queued stale
callbacks.

Issue #349 is a fourth, explicit extension. `V9PlayPlan` binds one checkpoint provider and one
frame-safe target to the authenticated manifest/consent tuple. The production `LinkedController`
adapter publishes an immutable identity/topology generation only from its controller event safe
point after consent and any transfer. Network and capture workers never read live controller
identity or topology directly. The provider selects the initial detached root, exact frame, and
post-transfer ROM/profile identities together at a later safe point, then performs StateFile-v2
encoding off the emulation owner; handshake progress therefore cannot stale a caller-supplied
initial frame. The host sends an initial MACHINE root in normal mode or one logical-full-roster
LINKED_SESSION in four-player mode. A four-player coordinator owns one bounded capture worker and
provider, captures that LINKED_SESSION once, and gives all three guest connections owned copies;
advancing emulation cannot produce three different activation roots. Closing the plan cancels all
capture waiters, closes the provider, and wipes retained encoded state where practicable without
letting one guest close the other guests' shared owner. Empty physical ports remain null in the
root while the logical player mask remains `0f`.
The peer validates the direct CGBS StateFile v2 off the emulator thread, prepares and retains exact
target-owned reconstruction without live mutation, and commits only after START at the
controller's frame safe point. Connection close owns pending generation capture, prepare, and
queued commit: if close wins before the first safe-point mutation, the result is `CANCELLED`, the
grant remains unused, and the retained transaction is discarded. If the controller safe point
wins first, close waits for that one atomic apply-or-rollback outcome and its single completion.
Task registration and close use the same ownership lock, so close cannot miss a just-created
capture/prepare worker. READY is admitted only after that guest's atomic commit. A received READY
is the guest protocol endpoint's assertion that its local commit completed; the host cannot
independently inspect the remote controller. The host publishes its four-player ACTIVE boundary
only after all three such responses, while a client publishes only after its READY bytes are
written.
In ACTIVE, normal resynchronization is SESSION and four-player resynchronization remains
LINKED_SESSION. INPUT, RESET, and STOP carry stable masks/IDs and are accepted only at the same
bounded frame-safe event boundary. In four-player mode each guest may originate only its
authenticated player. The host applies that value first and then fans the accepted stable
player/frame payload out to the other ACTIVE guest connections; those clients accept roster
relays only from their host connection and reject direct/spoofed player claims. Host player zero
is fanned out to every ACTIVE guest. Relay destinations are snapshotted under the coordinator lock
and offered in one total order to per-destination 256-value FIFO handoffs outside it. The
emulation/safe-point callback never enters writer-state admission or socket I/O; one
connection-owned worker performs those operations later. A blocked writer therefore cannot stall
the controller or healthy peers, while a full handoff closes only that destination with the typed
queue-overflow path. Close clears the handoff and interrupts the worker.
History exhaustion and invalid frame windows reject the connection before input mutation.

The checkpoint declaration, directional consent grant, manifest pair, root, owner/mask/channel,
unsigned increasing frame, SHA-256, exact ROM/slot identities, canonical profile/bootstrap/
accessory identity, StateFile checksum/schema, endpoint, and topology are validated before live
mutation. One direct StateFile is limited to 32 MiB encoded and decoded; aggregate decoded
retention remains 128 MiB; one directional grant admits at most 32 successfully committed
checkpoints and one in flight. StateFile v1, native serialization, the local legacy importer,
CGBN/Memento, outer checkpoint compression, and fallback are unreachable from peer bytes. A
failed prepare or commit does not consume the grant. Commit verifies that sessions, link history,
and identities are still the exact generation used by prepare; it never reconstructs against a
changed target. `LinkedController` captures rollback state, configuration/buffer ownership,
history, frame floor, and inputs and restores them if any member of the frame-safe transaction
fails. A disconnected four-player guest may reuse the frozen activation checkpoint only while the
provider still reports the same generation and frame. If the source has advanced or its topology
has changed, replacement is rejected as `TOPOLOGY_MISMATCH`; the caller must create a coherent new
activation generation rather than silently starting from stale bytes. Closing a caller-owned
normal provider, or the four-player coordinator that owns its shared provider, cancels a pending
safe-point capture and wipes encoded bytes if cancellation races encoding.

Normal ACTIVE SESSION resynchronization is explicit and remains available while the connection is
healthy. A late input older than retained history is a terminal typed `SEQUENCE_ERROR` with no
input mutation; the closed connection does not automatically reopen itself for a checkpoint.
Resynchronization must therefore occur before that terminal boundary, or through a newly
authenticated generation.

Callers that omit a play plan retain the Part-3 pre-START boundary; callers that omit Part 3 retain
the Part-2 boundary; callers that also omit a manifest plan retain the Part-1 post-AUTH boundary.
Diagnostics/discovery and Mobile Adapter behavior remain unavailable. The default
`ConnectionController` still has no v9 reference; shipped netplay remains protocol v8.

## Ownership, errors, and privacy

The transport owns its channel, reader/writer tasks, deadline task, bounded queue, and retained
frames. Cancellation or timeout closes each resource idempotently and cannot block an emulator
thread or the Swing EDT. A failed candidate is closed independently; it does not terminate the
listener or another candidate.

The injected monotonic lifecycle scheduler is authoritative for blocking-read cancellation.
Production sockets use no independent `SO_TIMEOUT` after connect, so the 5,000 ms HELLO/AUTH
deadline cannot preempt the frozen 10,000 ms pre-MANIFEST deadline. Closing the socket at the
current lifecycle deadline unblocks the reader and releases authentication/slot ownership.

A local peer-visible rejection first freezes queue and decoder admission and records its original
typed failure. The implementation then makes one best-effort terminal ERROR write, half-closes
output, and enters `TERMINAL_CLEANUP`. It drains only for peer EOF; it does not parse another
frame. Peer EOF closes promptly. Otherwise the injected monotonic cleanup deadline closes the
candidate at exactly 2,000 ms (it remains open at 1,999 ms) without replacing the rejection with
`TIMEOUT`. A failed error write also closes only that candidate while preserving the original
reason. Received terminal errors may close promptly.

Server ownership includes pending candidates and every handed-off connection, whether it remains
at `AWAITING_PAIRING`, the Part-1 pre-MANIFEST boundary, the Part-2 pre-consent boundary, or the
Part-3 pre-START boundary, or an opt-in #349 ACTIVE session.
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

Invitation authentication, bounded manifest metadata, explicit consent, bounded ROM/battery
preparation, and checkpoint/gameplay are implemented only through explicit opt-in plans;
encryption is **not** implemented. The protocol is plaintext TCP and does not provide
confidentiality or protection against an on-path attacker. The developer foundation must not be
presented as secure Internet netplay or as the default user flow.

## Verification

`ProtocolV9ProductionTest` compares production identities and limits against every frozen registry
row and exercises fragmentation, coalescing, truncation, header precedence, exact/+1 limits,
checked aggregate admission, compressed corruption/bombs, sequence exhaustion, HELLO negotiation,
deadline/cancellation cleanup, partial writes, and accept-loop survival. The independent Phase-0
reference model and hostile vectors remain unchanged. `ProtocolV9InvitationAuthTest` executes
every frozen invitation, invitation-lifecycle, and AUTH vector against production, including
concurrent one-use ownership and real-socket candidate isolation. `V9SwingLifecycleAdapterTest`
and `V9SwingInvitationAdapterTest` prove EDT delivery, explicit clipboard disclosure, redaction,
and the controller/Swing dependency boundary. `ProtocolV9ManifestTest` executes the frozen mapper,
difference, direction, and pre-consent classification rows through the production codec; exercises
all truncations, fragmented/coalesced frames, class/capability/content bindings, exact manifest
deadlines, normal/four-player exchanges, listener isolation, and the no-later-payload boundary.
`ProtocolV9ConsentTransferTest` covers exact consent/bulk schemas, both decision orders and strict
subsets, multiple ROM/battery proposals, lazy-provider gating, raw/DEFLATE framing, real-socket
normal/four-player exchanges, guest isolation, source/sink failure atomicity, exact progress
deadlines, and the pre-START gate. `V9SwingPart3AdapterTest` covers EDT delivery, sanitized
progress, explicit decisions/cancel, and queued stale-callback suppression.
`ProtocolV9StateTransportTest` covers direct-v2 admission, validation precedence, non-consuming
grant failures, bytewise/irregular/coalesced decode, exact deadlines, normal/four-player real
socket activation, active resynchronization, authenticated guest fan-out/rollback, spoof
rejection, STOP/RESET, downstream isolation, and gameplay bounds. `LinkedControllerTest` covers
frame-safe two-stage MACHINE and LINKED_SESSION commits, deterministic continuation, rollback on
failure, cancellation before safe-point mutation, and the close-versus-safe-point atomic winner.
Its ROM-independent continuation locks are
`909608afe8ea1510ea187b080ce48818b5b1f62379a0e23dde31cbb7ad61bd99` for the normal machine
path and `f19740d73c745a76b8bb7a2898ff308bec7d8e855d191efb8f248108a33149ae` for the four-player
linked path; both hash the canonical StateFile-v2 encoding after deterministic continuation.
