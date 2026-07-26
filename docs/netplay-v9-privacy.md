# Protocol-v9 pairing, privacy, and troubleshooting

Protocol v9 is a reviewed future contract; it is not available in the current build. Current
netplay remains protocol v8 with the compatibility restrictions in
[netplay-protocol-v8.md](netplay-protocol-v8.md). A v8/v9 mismatch is intentional and has no
downgrade. Use matching Coffee GB builds and do not repeatedly paste an invitation into an older
client.

## What an invitation does—and does not do

A v9 invitation will contain a random, one-use, short-lived 128-bit token. Possession proves only
that the peer received that invitation. The token stays in memory, is never logged/persisted, and
wrong, expired, reused, or wrong-slot attempts all receive the same generic failure. Revoke an
invitation by cancelling the host dialog or stopping the session.

V9's planned TCP transport is plaintext. It does not encrypt ROM, battery, state, input, or
metadata and cannot authenticate a server against an on-path attacker. It is not safe merely
because the URI contains a random token. V9 does not provide TLS, matchmaking, NAT traversal,
relay, or public Internet hardening. Prefer a trusted local network or a separately secured tunnel
whose operator you trust.

## Consent and data minimization

Own-ROM use is the default. Authentication and a compatible metadata-only manifest happen before
the UI can consent to ROM, battery, or checkpoint classes. A ROM mismatch never starts an
automatic transfer. Both sides must explicitly consent to each class, and cancellation denies it.
Paths, tokens, credentials, battery/state hashes, and payload fragments are forbidden in logs and
diagnostics. State transport is direct bounded StateFile v2 only; local historical snapshot import
is never reachable from a peer.

## Diagnosing a failed pairing

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
