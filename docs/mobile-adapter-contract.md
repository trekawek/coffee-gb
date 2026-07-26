# Mobile Adapter GB clean-room contract

This Phase #346 contract freezes only evidence-backed Game Boy Color serial framing and a future
Coffee GB ownership boundary. It is not a production implementation and does not emulate Nintendo
services. Architecture and licensing are normative in
[ADR 0002](adr/0002-mobile-adapter-clean-room.md); machine-readable evidence and transcripts live
under `core/src/test/resources/mobile-adapter/`.

## Pinned evidence and disagreement

Primary protocol evidence is Dan Docs `dandocs.html` at Shonumi commit
[`490595c3b8506d3f155aa6be9d7a5cd7d0fa9a5b`](https://github.com/shonumi/shonumi.github.io/blob/490595c3b8506d3f155aa6be9d7a5cd7d0fa9a5b/dandocs.html),
GPL-3.0, accessed 2026-07-26. The relevant sections specify:

- Game Boy Color internal-clock serial operation, packet magic/header/data/checksum/ack layout;
- `99 66` magic, four-byte header, big-endian data length and additive 16-bit checksum excluding
  magic, and a maximum GBC data length of 254 bytes;
- checksum acknowledgement error `f1`, unsupported `f0`, internal/buffer error `f2`;
- command `10` begin session with the exact eight ASCII bytes `NINTENDO` echoed;
- command `16` reset with empty request/response;
- command `19` configuration read with offset, length, maximum 128-byte request, and returned
  offset plus data;
- 256-byte configuration address space (192 bytes currently described), two connection slots, and
  a three-second serial-idle sleep/reset that cancels work; and
- an approximately 100 ms wake/toggle interval and a one-second no-data transfer wait.

One flow table says `96 66`, contradicting the same document's packet-format table `99 66`.
Pinned `REONTeam/libmobile` commit
[`0704f56902f23b7ebf05c82c222e0e145e3140b6`](https://github.com/REONTeam/libmobile/tree/0704f56902f23b7ebf05c82c222e0e145e3140b6),
LGPL-3.0, accessed 2026-07-26, independently checks/emits `99 66` and uses the same command IDs.
Coffee GB therefore freezes `99 66`; `96 66` is recorded as a documentation typo/disagreement.
No libmobile code, control flow, data structure, or text is copied.

The 100 ms wake statement is approximate and therefore is not used as an exact command deadline
in these fixtures. The exact deterministic Phase #351 engine contract uses an injected 3,000 ms
idle reset and exposes wake delay as an explicit configurable/evidence-review field until hardware
measurement resolves it. Telephone status values, ISP/server behavior, undocumented command
effects, service content, and GBA SIO32 behavior remain unknown/out of scope.

## Frozen GBC packet subset

A packet is exactly:

| Offset | Width | Field |
|---:|---:|---|
| 0 | 2 | magic `99 66` |
| 2 | 1 | stable command byte |
| 3 | 1 | reserved, exactly `00` |
| 4 | 2 | data length, unsigned big-endian `0..254` |
| 6 | n | data |
| 6+n | 2 | unsigned big-endian sum of bytes 2 through 5+n, modulo 65536 |

Acknowledgement is a separate two-byte exchange. A valid receiver reply uses its device ID OR
`80`, then request command XOR `80`. Synthetic fixtures choose device ID `08`, hence first byte
`88`; this is fixture configuration, not a universal hardware ID. Invalid checksum is `f1`;
unsupported is `f0`; bounded internal failure is `f2`. The transcripts model packet and acknowledgement channels separately rather than
guessing undocumented per-clock garbage/wait bytes.

The Phase #351 minimum supported command set is deliberately conservative:

| Command | Status | Request/response contract |
|---:|---|---|
| `10` | supported minimum | exactly 8 ASCII `NINTENDO`; echo exactly those 8 bytes |
| `11` | supported minimum | empty end-session request/response; enter sleep and clear slots/parser |
| `16` | supported minimum | empty reset request/response; cancels jobs/connections |
| `19` | supported minimum | 2-byte offset/length, length `0..128`, checked within 256; response offset+bytes |
| `1a` | specified, later opt-in | offset plus `0..128` bytes, checked within 256; atomic write, response offset |
| all others | unknown/unsupported | consume bounded packet, return `f0`, no state/backend mutation |

Recognizing framing is not evidence for implementing telephone, DNS, TCP/UDP, ISP, firmware, or
service semantics. Later commands require a reviewed evidence update and tests. Production must
never connect to historical Nintendo endpoints or ship endpoint presets/data.

## Deterministic core and controller bounds

The future core engine validates before allocating or indexing:

| Resource | Frozen limit |
|---|---:|
| packet data | 254 bytes |
| packet bytes including magic/header/checksum | 262 bytes |
| configuration address space | 256 bytes |
| one configuration read/write | 128 bytes |
| deterministic parser input/output buffers | 262 bytes each |
| deterministic pending packet slots | 2 |
| asynchronous controller request slots | 8 |
| complete backend buffered bytes per peripheral | 65,536 bytes |
| emulated idle reset | 3,000 ms since last serial byte |
| future connection identifiers | 2, only after a later evidence-backed command phase |

Length and `offset + length` use checked arithmetic. Invalid magic/reserved/length/checksum,
boundary+1, timeout, unsupported command, and full queues produce stable core status without
partial configuration mutation or backend admission. A complete valid packet is committed only
after checksum and command-specific validation.

## Timing, fragmentation, and lifecycle

The core engine accepts arbitrary serial-byte fragmentation. It advances only when the emulator
supplies a byte or explicit emulated-time delta. Host wall time is forbidden. At exactly 3,000 ms
idle no reset has occurred; the first tick beyond 3,000 ms cancels the partial packet, closes
logical connections, ends the session, clears bounded response state, and returns to sleep. A
following valid begin-session packet starts cleanly.

The Phase #346 executable reference receiver is genuinely incremental: it retains at most the
fixed 262-byte packet buffer across feed calls, decides magic after byte 2, reserved status after
byte 4, and length after byte 6, then waits for exactly the declared data and two checksum bytes.
Partial magic, header, data, or checksum returns `NEED_MORE` with no acknowledgement, response,
configuration mutation, or slot mutation. Invalid magic/reserved/length clears retention
immediately. Checksum or command validation occurs only once the complete request is present.
The transcript runner feeds every timestamped fragment rather than concatenating it first.

Every new non-empty feed starts a new visible operation result: a prior success or error cannot
leak while the next packet is partial. The engine therefore reports `NEED_MORE` with empty
response, acknowledgement, and commit count until that new packet completes. Emulated timestamps
are monotonic. A timestamp below the last observed value reports `TIME_REGRESSION` and changes
neither parser bytes, session state, slots, configuration, response, nor acknowledgement.

END_SESSION command `11` requires empty data, returns an empty `91` response and acknowledgement
`88 91`, transitions to sleep, and clears parser retention and both pending slots. Controller
cancellation, peripheral replacement, and session replacement have the same cleanup ownership:
they clear partial bytes, slots, response/acknowledgement, and commit markers, invalidate the
generation, and expose no stale completion to a following packet.

Reset command `16`, peripheral replacement, ROM/session replacement, save/load, rewind, stop, and
controller cancellation disconnect every live backend operation. Save state owns only the bounded
deterministic fields listed in ADR 0002. Loading deterministic state never reconnects host
resources. Backend completion is queued and applied at a controller safe point; late completion
from a cancelled generation is discarded. Configuration writes validate a detached copy and
commit atomically. Filesystem persistence, if later added, uses the existing crash-safe writer.

## Transcript format and legal status

`transcripts.tsv` contains synthetic Coffee GB packets. `request_hex` and `response_hex` are exact
packet bytes; `ack_hex` is the receiver acknowledgement/error. `fragments` is a semicolon-separated
list of `emulated-ms:byte-count` steps; times are monotonic and counts consume the request exactly,
except timeout cases ending with a zero-byte clock advance. SHA-256 values cover the concatenated
binary fields in the order request, response, ack. Tests validate framing, checksum, limits,
timing, result, and provenance without a production engine or external network.

The configuration bytes are authored synthetic values (`MA`, status `81`, zero padding), not a
device dump or credential. `NINTENDO` appears because the public protocol requires that ASCII
command constant; it is not firmware, a boot ROM, or a proprietary asset. No commercial ROM,
BIOS, save/state, credential, endpoint, server response, capture, or third-party source is present.
