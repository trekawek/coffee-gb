# Protocol-v9 pairing, privacy, and troubleshooting

Protocol v9 is an opt-in developer transport that validates CGB9 frames and negotiates HELLO
capabilities. Caller-owned plans add strict invitation/AUTH handling, bounded MANIFEST exchange,
two-sided item consent, ROM/battery preparation, direct StateFile-v2 checkpoints, START/READY, and
bounded ACTIVE traffic. Callers that do not supply a plan retain its earlier immutable boundary.
This is a playable developer foundation, not an end-user path. Current user netplay
remains protocol v8 with
the compatibility restrictions in
[netplay-protocol-v8.md](netplay-protocol-v8.md). A v8/v9 mismatch is intentional and has no
downgrade, fallback, or compatibility probe.

## What an invitation does—and does not do

The invitation host creates random, one-use, short-lived 128-bit invitations. Possession proves only
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

V9's opt-in TCP transport is plaintext. It does not encrypt ROM, battery, state, input, or
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
The minimal invitation Swing adapter parses pasted invitations away from the EDT, returns presentation
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
- **State/profile/root/topology mismatch:** the direct StateFile-v2 checkpoint did not match the
  approved ROM/slot identities, canonical hardware settings, endpoint, roster, or required root.
  The directional grant is not consumed and the live controller is unchanged.
- **Malformed/limit/checksum error:** the offending session is closed and its slot released; the
  listener and other sessions continue. Do not post payload bytes or an invitation in a bug report.

The exact grammar, limits, timeouts, errors, and state transitions are normative in
[netplay-protocol-v9.md](netplay-protocol-v9.md).

## In-session diagnostics and copied reports

Transport and rollback diagnostics are opt-in local measurements. A snapshot contains stable
role/mode/lifecycle/slot values, local-clock RTT aggregates, bounded ping counts, saturated byte
counts, connection duration, frame/remote-input age, and bounded rollback/history aggregates. It
does not contain invitation material, content payloads, peer diagnostic strings, ROM title or
digest, input masks, save/state bytes, or paths. Peer PING timestamps are never trusted for RTT,
timeouts, emulation, or host clock state.

Copied diagnostics are constructed from that whitelist and capped at 8,192 characters; they are
not exception or object dumps. Address is `redacted` by default. The Swing panel offers a separate
explicit local checkbox to include only the numeric peer address and port; tokens remain
impossible to include. Defense-in-depth text sanitization bounds input to 4,096 and output to
1,024 characters and replaces invitation URIs, credentials, POSIX/Windows paths, DNS names,
IPv4/bracketed IPv6 addresses, and controls. Runtime INPUT/RESET/STOP values and raw peer failures
are not logged at INFO.

## Trusted-LAN discovery

Discovery is off by default and is address discovery only. While explicitly enabled and a v9 host
is listening with an open slot, the local multicast advertisement contains protocol major 9, a
random 128-bit **public** session ID, normal/four-player mode, open-slot count, pairing-required
`true`, and the listener port. It contains no invitation token, ROM title/identity/hash, build,
user, path, credential, content, or state. Results are untrusted, bounded, deduplicated by public
session/address, and expire after five seconds. A forged LAN advertisement may mislabel or redirect
an endpoint; possession of the separate invitation and explicit local confirmation are still
required, and the existing AUTH/MANIFEST/two-sided-consent checks remain unchanged.

The implementation uses TTL-1 multicast only; there is no central/Nintendo service, broadcast
Internet scan, matchmaking, NAT traversal, UPnP, relay, automatic connection, or tokenless nonce
flow. Discovery failure is isolated from direct invitation hosting. Plaintext TCP remains visible
and mutable to an on-path attacker even when a host was discovered locally.

## Explicit play boundary

After successful AUTH, explicitly prepared peers exchange bounded MANIFEST metadata. An exact pair
with no proposals reaches `SYNCHRONIZING` without private traffic. Valid advanced proposals require
an explicit local source/target decision on each side; only the complete matching decision set may
open one lazy ROM/battery source. A verified transaction ends at an immutable preparation-complete
`SYNCHRONIZING` boundary. Manifest preparation itself still supplies hashes, sizes, sanitized
title/type/profile identity, and availability only—never ROM, battery, StateFile, or path bytes.

Only an additional `V9PlayPlan` can cross that boundary. Its production LinkedController target
publishes one immutable identity/topology generation at its frame safe point; foundation, network,
and capture workers do not inspect live controller ownership. The provider later captures one
detached root, the actual current frame, and post-transfer identities together at that safe point
and encodes off-owner, so emulation may keep advancing during the handshake. The transport never
consults the local legacy importer. The initial direct StateFile-v2 checkpoint is prepared without
mutation and committed at the frame safe point. READY is admitted only after that guest commit;
the host treats it as the guest protocol endpoint's assertion of local completion (it cannot
inspect the remote controller) and opens four-player ACTIVE only after all three responses.
Closing before the first safe-point mutation cancels capture/prepare/queued commit without using a
grant; if the safe point already won, close waits for its single atomic apply-or-rollback outcome.
Normal ACTIVE resynchronization uses a SESSION root; four-player uses one
coordinator-owned, generation-frozen LINKED_SESSION with logical mask `0f` and may preserve null
physical ports. Coordinator close cancels its bounded shared capture worker and waiters. A
replacement guest may reuse the captured generation only if the source generation and frame are
unchanged; otherwise it is rejected and pairing must restart with a new coherent generation.
Rejection wipes retained state
bytes where practicable, releases the candidate, and preserves the live sessions/history/input/
topology transactionally. No path, token, ROM, battery, StateFile bytes, digest, remote title, or
input value is emitted in INFO diagnostics.

During four-player ACTIVE traffic, each guest originates only its authenticated player. The host
applies accepted INPUT/RESET/STOP first and fans it out to the other ACTIVE guest connections;
clients accept roster-player relays only from that host connection. Relay I/O occurs without the
coordinator lock. The safe-point callback performs only a non-blocking offer into each bounded
256-value connection-owned FIFO; its worker owns later writer admission and socket I/O. A blocked,
failed, or full destination cannot block the controller or another guest, and close clears the
pending values before releasing that destination without revoking healthy sessions.

A healthy normal ACTIVE connection may request a SESSION resynchronization. Input older than the
retained rollback history instead terminates with a typed sequence failure before mutation; after
that terminal boundary a fresh authenticated generation is required rather than an implicit
checkpoint retry.

Only the bounded diagnostics and address-only trusted-LAN discovery described above are available.
V9 has no tokenless or manual-address authentication flow. A different invitation or
authentication mechanism requires a separately reviewed capability and threat-model change.
