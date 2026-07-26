# Synthetic Mobile Adapter contract transcripts

These resources are the Phase #346 clean-room contract, not captured traffic and not a production
engine. Packet bytes are generated from the public format in `docs/mobile-adapter-contract.md`.
Configuration content is synthetic, contains no account/server/credential data, and uses only the
ASCII marker and bounds described by the source. Hashes cover decoded request, response, and
acknowledgement bytes concatenated in that order.

`manifest.tsv` lists every other artifact in this directory and freezes its whole-file SHA-256,
kind, provenance, and reproduction method. It deliberately does not hash itself.

Every transcript records the 262-byte parser-buffer and two-pending-packet-slot ceilings. The
exact 3,000 ms partial-packet boundary remains waiting; the first injected time beyond it resets.

Reproduce a packet by writing `99 66`, command, zero reserved byte, big-endian payload length,
payload, and the big-endian 16-bit additive sum of command/reserved/length/payload. Responses use
request command OR `80`. The JUnit contract test independently reconstructs every packet and hash.
Normal tests perform no network, DNS, filesystem backend, or artifact regeneration.

Do not extend a command from `unsupported` using emulator source alone. Add pinned independent
evidence, license/redistribution status, uncertainty, a reviewed contract change, and synthetic
positive/boundary/malformed/timeout transcripts first.
