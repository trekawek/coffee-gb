# Protocol-v9 pairing, privacy, and troubleshooting

Protocol v9 has an opt-in developer transport foundation that validates CGB9 frames and negotiates
HELLO capabilities, then stops at `AWAITING_PAIRING`. It is not a playable end-user path: no
invitation, authentication, manifest, consent, private transfer, or checkpoint is enabled. Current
user netplay remains protocol v8 with the compatibility restrictions in
[netplay-protocol-v8.md](netplay-protocol-v8.md). A v8/v9 mismatch is intentional and has no
downgrade, fallback, or compatibility probe.

## What an invitation does—and does not do

A later v9 pairing phase will create random, one-use, short-lived 128-bit invitations. Invitation
creation and validation are not implemented by the #347 foundation. When implemented, possession
will prove only that the peer received that invitation. The token stays in memory, is never
logged/persisted, and wrong, expired, reused, or wrong-slot attempts all receive the same generic
failure.

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

## Diagnosing a failed pairing

The #347 foundation exposes only typed, sanitized protocol/timeout/cancellation diagnostics for
developer tests. Pairing diagnostics below describe the frozen later-phase behavior and are not
yet reachable from the desktop.

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
