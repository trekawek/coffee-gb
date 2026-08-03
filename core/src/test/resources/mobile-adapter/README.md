# Synthetic Mobile Adapter evidence notes

These resources began as the Phase #346 clean-room contract and now include the append-only Phase
#351, #352, and #353 command-status inventory. They are not captured traffic. Packet bytes are
generated from the public format
in `docs/mobile-adapter-contract.md`. Configuration content is synthetic, contains no
account/server/credential data, and uses only the ASCII marker and bounds described by the source.
Hashes cover decoded request, response, and acknowledgement bytes concatenated in that order.

`sources.tsv` also pins the REON revision used as a local validation target. That row records
service provenance only; it is not protocol evidence and no REON server code, configuration, or
response data is included.

Every transcript records the 262-byte parser-buffer and two-pending-packet-slot ceilings. The
exact 3,000 ms partial-packet boundary remains waiting; the first injected time beyond it resets.
These rows are reviewable protocol notes, not a second executable implementation.

Reproduce a packet by writing `99 66`, command, zero reserved byte, big-endian payload length,
payload, and the big-endian 16-bit additive sum of command/reserved/length/payload. Responses use
request command OR `80`. Focused engine, endpoint, backend, controller, and configuration tests
exercise production behavior directly.

`commands.tsv` keeps the frozen `phase_351_status` and `phase_352_status` columns and appends
`phase_353_status`. The `custom-backend-direct-channel` historical value means that the
deterministic engine can offer and poll a bounded typed request when a controller backend has been
explicitly installed. The Phase #353 `custom-backend-wire-channel` value additionally means that
the endpoint schedules the request acknowledgement, poll/turnaround bytes, response packet, and
response acknowledgement. Dial, telephone status, ISP lifecycle, backend ownership, connection-ID,
state-capture, and error behavior are covered by focused engine and endpoint tests. The original
synthetic transcript corpus remains frozen rather than relabeling new implementation tests as
captured traffic.

Do not extend a command from `unsupported` using emulator source alone. Add pinned independent
evidence, license/redistribution status, uncertainty, and a reviewed contract change first. Add
synthetic positive/boundary/malformed/timeout transcripts only when the response's on-wire schedule
is itself frozen; a direct backend vector must not be mislabeled as a serial transcript.
