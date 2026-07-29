# Mobile Adapter GB clean-room contract

Phase #346 froze only evidence-backed Game Boy Color serial framing and the Coffee GB ownership
boundary. Phase #351 now implements that conservative subset as a deterministic offline engine;
it still does not emulate Nintendo services. Architecture and licensing are normative in
[ADR 0002](adr/0002-mobile-adapter-clean-room.md); machine-readable evidence and transcripts live
under `core/src/test/resources/mobile-adapter/`.

## Implemented Phase-1 boundary

`MobileAdapterEngine` incrementally consumes one unsigned serial byte at a time and exposes
immutable response-packet and acknowledgement results as separate channels. The engine owns no
host resource. `MobileAdapterSerialEndpoint` feeds complete Game Boy bytes into it and returns an
idle-high input bit because the pinned evidence does not define how those two result channels are
interleaved with later polling transfers. This deliberate limitation keeps transcript conformance
testable without guessing an on-wire schedule or claiming commercial-game compatibility.

The core also defines a nonblocking backend ownership port with eight request/result slots and a
65,536-byte aggregate buffer. Phase 1 wires only a disconnected implementation and a deterministic
in-memory fake used by lifecycle and limit tests. No supported command submits backend work.
Reset, end, idle timeout, detach, replacement, and state restore cancel the port and discard late
ownership; they cannot open a host resource.

Every backend offer and completion carries the port's current opaque, identity-only generation
token. Cancellation replaces that token before numeric request IDs may be reused, so a late
completion from an older generation is rejected instead of completing a newer request with the
same ID. The token owns no host data and is never serialized.
The deterministic fake linearizes this bounded ownership with an immutable snapshot and one
lock-free atomic reference. It creates no worker, task, future, executor, callback, or blocking
wait; future host jobs remain controller-owned and publish only at controller safe points.

The controller replaces the former independent runtime booleans with one
`SerialPeripheralSelection`: none, printer, Barcode Boy, GPS receiver, Mobile Adapter GB, or the
ordinary peer endpoint. A candidate is prepared at the frame safe point, installed atomically,
then the old endpoint is disconnected. A failed preparation or handoff leaves the previous owner
active. Legacy boolean events remain source-compatible adapters: `true` selects that peripheral;
`false` releases it only when it is still the owner. Coffee GB never persisted those three old
runtime booleans, so there are no historical disk keys to guess or reinterpret. The deterministic
startup migration is the prior peer endpoint, and queued legacy conflicts resolve in event order.

Swing presents the same enum as one radio group plus immutable attachment status and stable typed
errors. The read-only Mobile Adapter details action shows the cached configuration source, device
ID, recovery status, and any typed redacted diagnostic while replacing all 256 private bytes with
an explicit hidden marker. It also shows the offline command/privacy boundary; it contains no host,
address, credential, dial string, or real-network action. Raw configuration editing and service
fields belong to the approved Phase #352 follow-up. Controller callbacks are copied and rendered
on the Swing event-dispatch thread.

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
or implemented as a configurable timing value in Phase #351. The deterministic engine models only
the injected 3,000 ms idle reset; wake/toggle timing remains an evidence gap until hardware
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

The core engine validates before allocating or indexing:

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

Protocol connection identifiers require the service commands and disconnected-state semantics
owned by Phase #352, so Phase #351 deliberately does not invent mutable connection IDs. This
phase enforces the independently evidenced two pending packet slots and the eight-slot backend
ownership ceiling; #352 must add at most two logical connection identifiers before opting any
network command into the supported table.

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

The Phase #346 executable reference receiver and the Phase #351 production engine are genuinely
incremental: each retains at most the
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

Reset command `16`, peripheral replacement, ROM/session replacement, state load/restore, rewind,
stop, and controller cancellation disconnect every live backend operation. State capture/save is
observational and does not cancel or disconnect backend work; it owns only the bounded
deterministic fields listed in ADR 0002. Loading deterministic state never reconnects host
resources. Backend completion is queued and applied at a controller safe point; late completion
from a cancelled generation is discarded. Configuration writes validate a detached copy and
commit atomically.

The stable engine IDs serialized by Phase 1 are append-only:

| Kind | Stable IDs |
|---|---|
| phase | `SLEEP=1`, `SESSION=2` |
| outcome | `NEED_MORE=1`, `SESSION_STARTED=2`, `SESSION_ENDED=3`, `SESSION_RESET=4`, `CHECKSUM_ERROR=5`, `IDLE_TIMEOUT_RESET=6`, `IDLE_BOUNDARY_WAIT=7`, `CONFIG_READ=8`, `CONFIG_READ_BOUNDARY=9`, `UNSUPPORTED_COMMAND=10`, `MAGIC_ERROR=11`, `RESERVED_ERROR=12`, `LENGTH_LIMIT=13`, `BUFFER_LIMIT=14`, `TIME_REGRESSION=15` (transient), `CANCELLED=16`, `PENDING_LIMIT=17` |
| error | `NONE=0`, `INVALID_MAGIC=1`, `RESERVED_VALUE=2`, `LENGTH_LIMIT=3`, `CHECKSUM=4`, `UNSUPPORTED_COMMAND=5`, `BUFFER_LIMIT=6`, `TIME_REGRESSION=7` (transient), `PENDING_LIMIT=8` |

The engine memento contains phase/outcome/error/device IDs; the fixed 262-byte parser and retained
count/expected length; the 256-byte configuration; bounded response and acknowledgement arrays;
exact emulated idle phase; serial-input ownership; and the two-slot count. The endpoint memento
adds the current serial byte and bit index. Both records defensively copy arrays. Restore validates
the complete graph before mutation, cancels the injected backend, and never captures that backend.

## Atomic private configuration

Desktop startup loads configuration on the launcher thread, before entering Swing, and supplies a
cached immutable value to the controller. The emulator and EDT therefore perform no filesystem
work. The dedicated file is separate from general preferences and has one exact 300-byte format:

| Offset | Width | Field |
|---:|---:|---|
| 0 | 8 | ASCII magic `CGBMACFG` |
| 8 | 1 | format version `1` |
| 9 | 1 | device ID `0..127` |
| 10 | 2 | big-endian configuration length, exactly `256` |
| 12 | 256 | private configuration bytes |
| 268 | 32 | SHA-256 of bytes `0..267` |

The controller validates exact size, magic, version, bounds, and integrity before publishing a
detached value. It rejects symbolic links and non-regular targets, uses `AtomicFileWriter` for
replacement/recovery, restricts and verifies the temporary inode before either rename path, keeps
the last accepted immutable value after a failed reload, and applies owner read/write (`0600`)
permissions where POSIX permissions exist. Owner-only writes reject a group/world-writable parent,
never follow a substituted temporary symlink, and reverify the regular temporary artifact
immediately before the atomic and fallback moves. A permission-preparation failure therefore cannot
commit the new private bytes. As defined by
[the shared persistence contract](atomic-persistence.md), a failure reported after a completed
rename remains a conservative caller failure: the old in-memory last-good value is retained until
an explicit retry even though the complete new target may already be visible. A missing record with no
previously accepted persisted value is normal first-run state and uses the documented synthetic
configuration without a diagnostic. Invalid or unreadable storage, or a record that disappears
after this store instance accepted it, preserves the last-good value when available (otherwise it
uses the synthetic fallback) and returns a typed, redacted diagnostic. Payloads and full paths
never enter status text or logs.

The Phase-1 configuration UI is intentionally read-only. It reports whether the launcher selected
a validated record, recovered backup, last-good value, or synthetic fallback, plus the safe device
ID and stable diagnostic. It never receives the private byte array. Import/edit/reset controls and
online service fields are deferred to #352, alongside the command semantics that could use them.

## Troubleshooting the offline phase

- Select **Peripherals → Link-port device → Mobile Adapter GB (offline)**. Status changes to
  attached only after the controller commits the endpoint; selecting another radio item detaches
  and clears it.
- `CONFIGURATION_INVALID` means a bounded value failed validation. `STORAGE_FAILED` means the
  private record could not be loaded. Coffee GB uses a safe synthetic fallback at desktop startup
  and logs only the stable error code.
- `PORT_OWNED_BY_LINK` means an active linked/netplay controller still owns the serial port. Stop
  that link and retry the selection. If its persistence barrier fails, the radio rolls back and a
  later selection retries the retained linked-controller handoff even if the network toggle has
  already cleared.
- A game that waits forever for acknowledgement/response polling has reached the known evidence
  gap. Phase 1 intentionally returns idle-high rather than inventing those bytes.
- There is no server/address field in this phase. Telephone, DNS, TCP, UDP, listener, relay, and
  Nintendo service behavior remain Phase #352 or explicitly out of scope.

## Transcript format and legal status

`transcripts.tsv` contains synthetic Coffee GB packets. `request_hex` and `response_hex` are exact
packet bytes; `ack_hex` is the receiver acknowledgement/error. `fragments` is a semicolon-separated
list of `emulated-ms:byte-count` steps; times are monotonic and counts consume the request exactly,
except timeout cases ending with a zero-byte clock advance. SHA-256 values cover the concatenated
binary fields in the order request, response, ack. Tests validate framing, checksum, limits,
timing, result, and provenance against both the executable reference and production engine,
without an external network.

The configuration bytes are authored synthetic values (`MA`, status `81`, zero padding), not a
device dump or credential. `NINTENDO` appears because the public protocol requires that ASCII
command constant; it is not firmware, a boot ROM, or a proprietary asset. No commercial ROM,
BIOS, save/state, credential, endpoint, server response, capture, or third-party source is present.
