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

Checkpoints, START/READY, playable input, diagnostics, discovery, and Mobile Adapter behavior
remain unavailable. Their declarations are rejected before proportional payload allocation.
Callers that omit a Part-3 plan retain the Part-2 boundary; callers that also omit a manifest plan
retain the Part-1 post-AUTH boundary. The default `ConnectionController` still has no v9 reference;
shipped netplay remains protocol v8.

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
Part-3 pre-START boundary.
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

Invitation authentication, bounded manifest metadata, explicit consent, and bounded ROM/battery
preparation are implemented only through the opt-in Part-1/Part-2/Part-3 API; encryption is **not**
implemented. The protocol is plaintext TCP and does not provide confidentiality or protection
against an on-path attacker. This boundary must not be presented as a secure or playable Internet
netplay flow.

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
