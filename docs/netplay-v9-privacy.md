# Protocol-v9 pairing, privacy, and troubleshooting

Protocol v9 has an opt-in developer transport foundation that validates CGB9 frames and negotiates
HELLO capabilities. Part 1 of #348 adds strict invitation parsing, bounded host invitation
ownership, and AUTH. Part 2 adds the exact bounded MANIFEST metadata exchange, then stops at an
immutable pre-consent boundary. Part 3 optionally adds explicit two-sided item consent and bounded
ROM/battery preparation, then stops before START. It is not a playable end-user path: checkpoint,
input, and gameplay remain disabled. Callers that do not opt into an explicit prepared manifest
and Part-3 plan retain the earlier boundaries. Current user netplay remains protocol v8 with
the compatibility restrictions in
[netplay-protocol-v8.md](netplay-protocol-v8.md). A v8/v9 mismatch is intentional and has no
downgrade, fallback, or compatibility probe.

## What an invitation does—and does not do

The Part-1 host creates random, one-use, short-lived 128-bit invitations. Possession proves only
that the peer received that invitation. Token material stays in bounded session memory, is never
logged or persisted, and wrong, expired, reused, stopped-host, wrong-slot, and rate-limited
attempts all receive the same generic failure. Host expiry uses an injected monotonic deadline;
the URI's UTC `exp` value is display-only. Successful proof consumption and slot reservation are
one atomic decision.

Token bytes have explicit transfer ownership. Parsing creates one wipeable buffer; transferring
to client authentication makes the parsed invitation unusable without copying the token.
Host-generated invitation views share their buffer with the host ledger so use, expiry,
replacement, host stop, or explicit close invalidates every retained view. Rendering and clipboard
copy are explicit disclosure operations and fail after invalidation. A URI `String` already
returned to caller or clipboard code is immutable and cannot be zeroized; from that disclosure
point the caller owns its lifetime and must not log or persist it.

V9's planned TCP transport is plaintext. It does not encrypt ROM, battery, state, input, or
metadata and cannot authenticate a server against an on-path attacker. It is not safe merely
because the URI contains a random token. V9 does not provide TLS, matchmaking, NAT traversal,
relay, or public Internet hardening. Prefer a trusted local network or a separately secured tunnel
whose operator you trust.

## Consent and data minimization

Own-ROM use is the default. Authentication and a compatible bounded manifest happen first. The
manifest exposes only the frozen compatibility context, roster, sanitized cartridge title/type,
stable mapper family, content sizes/digests, and explicit advanced offer/request proposals; it
never contains a path or payload. A missing or different ROM is a warning requiring approval, not
an automatic transfer.

Consent is not a global class switch. Source and target each approve one proposal bound to both
exact manifests, class, direction, players, asset kind, size, and digest. ROM, battery, and
checkpoint approvals are distinct; a changed manifest or replayed/extra transaction rejects.
Paths, tokens, credentials, battery/state payloads, and payload fragments are forbidden in logs
and diagnostics. State transport is direct bounded StateFile v2 only; local historical snapshot
import is never reachable from a peer.

For the implemented ROM/battery classes, a mismatch alone opens nothing. The caller's lazy source
is invoked off the emulator thread and EDT only after both approvals. One approved item permits
one matching directional transaction. The receiver publishes nothing until length and SHA-256 are
complete and verified; partial retention and queued private frame copies are wiped on rejection,
timeout, cancellation, or close where the runtime permits. The presentation adapter sees only
item IDs, stable classes/assets, direction, bounded byte counts, and sanitized state.

## Diagnosing a failed pairing

The opt-in foundation exposes only typed, sanitized protocol/timeout/cancellation diagnostics.
The minimal Part-1 Swing adapter parses pasted invitations away from the EDT, returns presentation
events on the EDT, and accesses the clipboard only through an explicit copy action. It is not
wired into the normal netplay menu.

- **Unsupported protocol / `Coff` versus `CGB9`:** the builds use v8 and v9. Install matching
  versions; there is no fallback.
- **Authentication failed:** request a fresh invitation. The diagnostic deliberately does not say
  whether the old token expired, was used, or targeted another slot.
- **Manifest mismatch:** verify ROM and optional pass-through ROM hashes, exact hardware profile,
  bootstrap/accessory settings, and link mode. A mismatch is not consent to send a ROM.
- **Consent rejected:** one side cancelled, timed out, or declined a required class. Nothing large
  is transferred before this point.
- **Malformed/limit/checksum error:** the offending session is closed and its slot released; the
  listener and other sessions continue. Do not post payload bytes or an invitation in a bug report.

The exact grammar, limits, timeouts, errors, and state transitions are normative in
[netplay-protocol-v9.md](netplay-protocol-v9.md).

## Deliberate Part-3 boundary

After successful AUTH, explicitly prepared peers exchange bounded MANIFEST metadata. An exact pair
with no proposals reaches `SYNCHRONIZING` without private traffic. Valid advanced proposals require
an explicit local source/target decision on each side; only the complete matching decision set may
open one lazy ROM/battery source. A verified transaction ends at an immutable preparation-complete
`SYNCHRONIZING` boundary. Manifest preparation itself still supplies hashes, sizes, sanitized
title/type/profile identity, and availability only—never ROM, battery, StateFile, or path bytes.

Checkpoints, START/READY, diagnostics, discovery, and playable input remain for later phases.
Checkpoint and atomic linked-state integration are #349. The stale nonce-comparison roadmap
checkbox is not a tokenless/manual-address bypass: v9 has no such flow. Discovery or a different
invitation mechanism requires a separately reviewed capability and threat-model change and is
deferred to #350.
