# Synthetic Mobile Adapter contract transcripts

These resources began as the Phase #346 clean-room contract and now include the Phase #352 command
status inventory. They are not captured traffic. Packet bytes are generated from the public format
in `docs/mobile-adapter-contract.md`. Configuration content is synthetic, contains no
account/server/credential data, and uses only the ASCII marker and bounds described by the source.
Hashes cover decoded request, response, and acknowledgement bytes concatenated in that order.

`manifest.tsv` lists every other artifact in this directory and freezes its whole-file SHA-256,
kind, provenance, and reproduction method. It deliberately does not hash itself.

Every transcript records the 262-byte parser-buffer and two-pending-packet-slot ceilings. The
exact 3,000 ms partial-packet boundary remains waiting; the first injected time beyond it resets.
The reference engine also executes sequential complete-to-partial and error-to-partial feeds,
rejects regressing emulated timestamps without mutation, and clears parser/slot/response ownership
on END_SESSION, cancellation, and replacement.

Reproduce a packet by writing `99 66`, command, zero reserved byte, big-endian payload length,
payload, and the big-endian 16-bit additive sum of command/reserved/length/payload. Responses use
request command OR `80`. The JUnit contract test independently reconstructs every packet and hash.
Normal tests perform no network, DNS, filesystem backend, or artifact regeneration.

`commands.tsv` keeps the frozen `phase_351_status` column and appends `phase_352_status`. The
`custom-backend-direct-channel` value means that the deterministic engine can offer and poll a
bounded typed request when a controller backend has been explicitly installed. It does not mean
that the idle-high serial endpoint emits the response bytes. That evidence-dependent scheduling is
owned by #353, so no DNS/TCP/UDP row was added to `transcripts.tsv`. Direct-channel request,
completion, generation, queue, connection-ID, state-capture, and error behavior is instead tested
against the deterministic in-memory backend in `MobileAdapterEngineTest`.

Do not extend a command from `unsupported` using emulator source alone. Add pinned independent
evidence, license/redistribution status, uncertainty, and a reviewed contract change first. Add
synthetic positive/boundary/malformed/timeout transcripts only when the response's on-wire schedule
is itself frozen; a direct backend vector must not be mislabeled as a serial transcript.
