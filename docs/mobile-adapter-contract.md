# Mobile Adapter GB clean-room contract

Phase #346 froze only evidence-backed Game Boy Color serial framing and the Coffee GB ownership
boundary. Phase #351 implemented that conservative subset as a deterministic offline engine.
Phase #352 adds bounded custom-server DNS/TCP/UDP ownership and a direct core response channel;
Phase #353 adds the byte-pipelined link schedule and a minimal blue-adapter telephone/ISP state
machine modeled for Japanese Pokémon Crystal. It connects only to the user's explicitly configured
custom backend and does not emulate or select a Nintendo production service. Architecture and licensing are normative in
[ADR 0002](adr/0002-mobile-adapter-clean-room.md); machine-readable evidence and transcripts live
under `core/src/test/resources/mobile-adapter/`.
The real-ROM and custom-service results, including their remaining boundary, are recorded in
[mobile-adapter-crystal-reon-validation.md](mobile-adapter-crystal-reon-validation.md).
The retained Mobile Adapter configuration dialog also has a bounded, owner-selected adapter-image
import path. The current desktop keeps its Mobile Adapter controls hidden; the import control does
not make that retained dialog a generally exposed feature.

## Implemented deterministic boundary

`MobileAdapterEngine` incrementally consumes one unsigned serial byte at a time and exposes
immutable response-packet and acknowledgement results as separate channels. The engine owns no
host resource. `MobileAdapterSerialEndpoint` owns the byte-pipelined link schedule: request bytes
receive `d2`, the adapter emits the two request-acknowledgement bytes, one `d2` turnaround byte is
skipped, and then exactly one `4b` gate receives another `d2`. Invalid pre-gate input aborts the
wire transaction, revokes backend ownership, and accepts `99` as immediate request resynchronization.
After a valid gate, asynchronous backend commands remain in a distinct pending phase until a
controller completion is available; the next transfer then starts the response without consuming a
second gate. The Game Boy acknowledges the completed response; `f0`, `f1`, and `f2` restart directly
at response magic, at most four times, before the endpoint aborts the wire transaction. The engine
and endpoint capture their deterministic protocol state without capturing a socket or host task.

The core also defines a nonblocking backend ownership port with eight request/result slots and a
65,536-byte aggregate buffer. The engine admits at most one direct custom-server request at a time,
reserving one of its two deterministic packet slots until completion. Commands `15`, `23` through
`26`, and `28` may submit work only after `10` begins a session and only when the controller has
installed an enabled custom-server backend. No offer, poll, or completion callback waits. Reset,
end, idle timeout, detach, replacement, and state restore cancel the port and discard late
ownership. The disconnected port performs no host work and returns a coarse `6e` invalid-use
response for a valid direct command.

Every backend offer and completion carries the port's current opaque, identity-only generation
token. Completions additionally carry one sanitized status category. Cancellation replaces the
token before numeric request IDs may be reused, and expected-generation polling atomically refuses
to remove a result after that replacement. The engine checks the returned generation and request
ID again before applying it, so a late completion cannot complete a newer request with the same
numeric ID. The token owns no host data and is never serialized.
The deterministic fake linearizes this bounded ownership with an immutable snapshot and one
lock-free atomic reference. It creates no worker, task, future, executor, callback, or blocking
wait; host jobs remain controller-owned and publish only at controller safe points.

Phase #353 supplies only a synthetic service flow for reaching a custom server. Blue-adapter dial command
`12` accepts model prefix `00` plus the exact Mobile System GB ISP number `#9677`; it never places a
host telephone call. Status command `17` reports disconnected `00 4d 00` before dial and connected
`04 4d 00` after dial/login. ISP login command `21` validates bounded `length + ID`, `length +
password`, and two requested DNS addresses without authenticating, storing, logging, or forwarding
the credentials. Its deterministic response assigns guest address `127.0.0.1` and zero DNS response
fields, matching the pinned corroborating implementation when DNS was supplied by the request.
Logout `22` closes backend ownership and returns to the dialled telephone phase; hang-up `13` closes
backend ownership and returns to the disconnected session phase. Invalid state/body/number errors
are command `6e` with stable codes `01`, `02`, or `03` and a normal request acknowledgement.

Custom-server mode remains an out-of-band controller prerequisite for any host networking; the
guest telephone/ISP commands grant no network authority. A TCP/UDP open request is
exactly four IPv4 bytes and a nonzero big-endian port. A close request is exactly one live ID of the
matching transport. Transfer contains one live ID plus `0..253` bytes. DNS accepts a `1..254` byte
packet, truncates at the first NUL, and passes only a nonempty ASCII name of at most 253 bytes to the
backend. A successful backend result is accepted only when it has the exact command-specific
shape: a free connection ID `0..1`, the same close/transfer ID, at most 253 transfer data bytes, or
exactly four DNS result bytes.

Only evidence-backed coarse errors cross back into the packet engine. Invalid/disconnected use is
code `01`; connection-slot exhaustion is open code `00`; other open failures are code `03`; DNS
lookup/content failure is code `02`; and transfer/close failure is code `00`. A full engine/backend
queue or byte bound produces acknowledgement `f2` without admission. A typed remote TCP close
drops that logical ID and produces empty response command `9f`. Cancelled, stale-generation, and
state-restored ownership produces the stable external-I/O-disconnected state with no stale packet.

The controller replaces the former independent runtime booleans with one
`SerialPeripheralSelection`: none, printer, Barcode Boy, GPS receiver, Mobile Adapter GB, or the
ordinary peer endpoint. A candidate is prepared at the frame safe point, installed atomically,
then the old endpoint is disconnected. A failed preparation or handoff leaves the previous owner
active. Legacy boolean events remain source-compatible adapters: `true` selects that peripheral;
`false` releases it only when it is still the owner. Coffee GB never persisted those three old
runtime booleans, so there are no historical disk keys to guess or reinterpret. The deterministic
startup migration is the prior peer endpoint, and queued legacy conflicts resolve in event order.

Swing presents the same enum as one radio group plus immutable attachment status and stable typed
errors. Phase 1's read-only details action shows the cached configuration source, device ID,
recovery status, and any typed redacted diagnostic while replacing all 256 private bytes with an
explicit hidden marker. Phase 2 adds the separately consented custom-server controls without
exposing raw configuration, dial strings, credentials, or backend exception text. Controller
callbacks are copied and rendered on the Swing event-dispatch thread.

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

Pinned libmobile waits for the valid `4b` gate before applying a command. Coffee GB's released pure
engine API applies synchronous command effects when the checksum byte completes, before its endpoint
emits the acknowledgement and validates that gate. To preserve direct-engine behavior, an invalid
gate discards the response and revokes all backend ownership but does not roll back an already-applied
pure phase or configuration change. Deferring that commit would require an additive staged engine
state and remains an explicit fidelity gap rather than an inferred rollback rule.

The 100 ms wake statement is approximate and therefore is not used as an exact command deadline
or implemented as a configurable timing value in Phase #351. The deterministic engine models only
the injected 3,000 ms idle reset; wake/toggle timing remains an evidence gap until hardware
measurement resolves it. Phase #353 freezes only the blue-adapter Crystal outbound flow above;
inbound calls, direct telephone numbers, other adapter colors, unmetered status, undocumented
command effects, Nintendo service content, and GBA SIO32 behavior remain out of scope.

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

The Phase #351 minimum remains frozen. Phase #352 adds only the pure configuration write and the
controller-gated direct response channel:

| Command | Phase #352 status | Request/response contract |
|---:|---|---|
| `10` | supported minimum | exactly 8 ASCII `NINTENDO`; echo exactly those 8 bytes |
| `11` | supported minimum | empty end-session request/response; enter sleep and clear slots/parser/backend |
| `15` | custom backend, direct channel | live ID plus `0..253` bytes; reply same ID plus `0..253` bytes, or empty `9f` remote-close |
| `16` | supported minimum | empty reset request/response; cancels jobs/connections |
| `19` | supported minimum | 2-byte offset/length, length `0..128`, checked within 256; response offset+bytes |
| `1a` | supported pure operation | offset plus `0..128` bytes, checked within 256; atomic in-memory write, response offset |
| `23`, `25` | custom backend, direct channel | exactly IPv4(4)+nonzero port(2); reply one free ID in `0..1` |
| `24`, `26` | custom backend, direct channel | exactly one matching live ID; reply the same ID |
| `28` | custom backend, direct channel | packet `1..254`; first-NUL-truncated ASCII name `1..253`; reply exactly IPv4(4) |
| `6e` | adapter response only | original failed command plus evidence-backed coarse error code |
| `12`, `21`, `22` | unsupported | no dial-up, login/logout, credential, or Nintendo service emulation |
| all others | unknown/unsupported | consume bounded packet, return `f0`, no state/backend mutation |

Phase #353 preserves that historical column and appends this compatibility subset:

| Command | Phase #353 status | Request/response contract |
|---:|---|---|
| `12` | Crystal ISP dial | exactly blue prefix `00` plus `#9677`; empty response; enter `TELEPHONE` |
| `13` | hang up | empty; cancel backend; enter disconnected `SESSION` |
| `17` | blue telephone status | empty; response `00 4d 00` in `SESSION`, otherwise `04 4d 00` |
| `21` | Crystal ISP login | ID/password length fields each `0..32`, then DNS1(4)+DNS2(4); response `127.0.0.1` plus eight zero bytes; enter `INTERNET` |
| `22` | ISP logout | empty; cancel backend; return to `TELEPHONE` |
| `15`, `23`–`26`, `28` | custom backend, wire channel | Phase #352 bounded request/response contract scheduled by the serial endpoint; accepted in direct `SESSION` mode or after `21` enters `INTERNET` |
| `6e` | adapter response only | failed service/backend command plus stable coarse error, after the normal request acknowledgement |

The direct backend commands and the emulated service transition are usable only behind the reviewed
custom-server policy; they do not impersonate a production service or authenticate an account.
Coffee GB ships and automatically selects no Nintendo endpoint, preset, account, or service data.
The destination policy validates an exact configured alias/address, transport, port, address class,
and freshly resolved answer; it cannot infer who operates an otherwise eligible public IPv4
address. The user must therefore configure only an intended non-Nintendo custom service. Phase #352
and #353 tests use in-process endpoints and generate no Nintendo production traffic.

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
| direct backend requests outstanding in the engine | 1 |
| asynchronous controller request slots | 8 |
| complete backend buffered bytes per peripheral | 65,536 bytes |
| emulated idle reset | 3,000 ms since last serial byte |
| runtime logical connection identifiers | 2 (`0` and `1`), never serialized |
| transfer packet | 1-byte ID plus `0..253` data bytes |
| DNS packet/effective ASCII name | 254 / 253 bytes |
| dial body | 1-byte adapter model plus at most 32 dial-string bytes |
| ISP ID/password | 32 bytes each; never retained after command completion |

The controller backend is one daemon owner loop with no per-request task creation. Its public
offer, cancellation, status, and completion calls are nonblocking; only the owner loop opens or
waits on NIO channels. The remaining host-side limits are:

| Controller resource | Exact limit |
|---|---:|
| physical TCP/UDP connections | 2 total |
| prepared/terminating backend owner loops | 2 total |
| backend command payload | 254 bytes |
| status snapshots | 16, oldest replaced on overflow |
| raw DNS datagram | 512 bytes |
| DNS answer/authority/additional records | 32 total |
| DNS compression pointer jumps / CNAME hops | 16 per decoded name / 4 per selected chain |
| distinct IPv4 answers | 8 in the terminal selected result set |
| DNS / TCP connect timeout | 2,000 / 3,000 ms |
| socket write / read timeout | 1,000 / 1,000 ms |
| previously returned DNS capability | 10,000 ms, then discarded |
| selector cancellation polling interval | at most 100 ms |
| host-network retries | 0 |
| response-packet retransmissions requested by `f0`/`f1`/`f2` | at most 4 |

Cancel and refresh delivery uses one coherent constant-space controller lane: one latest cancel
order and one latest accepted policy revision/order. Bursts cannot grow the general event queue,
and relative order determines whether cancellation applies to the old or replacement endpoint.

Configuration persistence has one daemon writer. At most one explicit owner write may execute and
one may wait in its bounded queue; another policy/import request is rejected without enqueueing as
`CONFIGURATION_BUSY`. Changed guest configuration writes use a separate constant-space latest-image
slot. Accepting that slot immediately updates the coordinator's detached in-memory image, then the
same writer merges it with the latest durable device ID and policy. A newer guest image replaces an
unscheduled older one. A failed write leaves the acknowledged image active and dirty without
spinning; a later guest write, explicit configuration operation, or controller-close retry attempts
it again. A later successful owner import explicitly supersedes an older dirty guest image and
publishes a redacted terminal status. Coordinator shutdown uses one 2,000 ms deadline shared by
that writer and all prepared network backends. The writer receives at most 1,000 ms to stop
gracefully, then an interrupt request
and only the time still remaining; backend termination checks consume that same remaining deadline
rather than receiving a fresh timeout each. If a shutdown attempt times out, a later close retry
re-snapshots the still-tracked backends and waits under a new shared 2,000 ms deadline; it never
re-enables authority or starts a replacement worker.

DNS is a bounded raw UDP A query sent only to the configured literal IPv4 resolver; the JVM system
resolver is never called. A guest name is only an exact alias into the saved mapping table. A named
target is freshly resolved immediately before every open, every returned address is reclassified,
and the exact address requested by the guest must still be present. A changed or newly denied
answer fails closed. TCP and UDP are outbound and connected to the selected peer; there is no
listener, bind service, relay, proxy, discovery, unsolicited receive, or ambient DNS path.

The two logical IDs are a runtime validation view of controller-owned TCP/UDP resources. They are
created only by a validated successful open completion, cleared only by matching close, remote
close, or a cancellation boundary, and never captured as emulator state. The engine's one-request
ceiling is stricter than the eight-slot port ceiling so packet state and response ordering remain
unambiguous. The wider port bound still protects controller publication and deterministic fake
tests independently.

Length and `offset + length` use checked arithmetic. Invalid magic/reserved/length/checksum,
boundary+1, timeout, unsupported command, malformed backend result, and full queues produce stable
core status without partial configuration mutation or unintended backend admission. A complete
valid packet is committed only after checksum and command-specific validation.

Timeouts and transfer limits preserve transport framing:

- DNS and TCP-connect timeout fail that request without creating a connection slot.
- TCP write timeout closes the affected slot. A one-second TCP read with no data returns an empty
  successful TRANSFER and preserves the slot so a game can send a later request fragment. UDP read
  timeout closes its slot and reports typed `TIMEOUT`; the direct core channel consumes that close
  as an empty remote-close response `9f` so the logical ID cannot remain usable.
- UDP write timeout reports typed `TIMEOUT` but retains the connected slot. A datagram is never
  split and Coffee GB performs no automatic retry, so the caller must decide whether to issue a
  later transfer.
- A request above its byte bound is rejected before host I/O with `TRANSFER_LIMIT`. An inbound TCP
  stream is read in at most 253-byte chunks, leaving later bytes for subsequent TRANSFER commands.
  An oversized UDP datagram reports `TRANSFER_LIMIT` while preserving the slot because the
  datagram boundary was consumed atomically.
- TCP EOF before data produces `REMOTE_CLOSED` immediately. If data and EOF arrive together, the
  final bounded bytes are returned once and the physical connection is closed; the following use
  of that logical ID produces `REMOTE_CLOSED` and releases its tombstone. A slot-scoped failure
  carries the affected ID and the remaining `0..2` active-connection count, so closing one slot is
  not presented as a whole-adapter disconnect.

## Timing, fragmentation, and lifecycle

The core engine accepts arbitrary serial-byte fragmentation. It advances only when the emulator
supplies a byte or explicit emulated-time delta. Host wall time is forbidden. At exactly 3,000 ms
idle no reset has occurred; the first tick beyond 3,000 ms cancels the partial packet, closes
logical connections, ends the session, clears bounded response state, and returns to sleep. A
following valid begin-session packet starts cleanly. Starting any serial-byte transfer records link
activity before its reply is latched; completing request bytes also refreshes the parser-owned
interval. Timeout evaluation and controller backend polling continue while a byte is armed, but
the reply byte is latched at transfer start. A completion becomes visible to the wire scheduler
only at the byte boundary. Timeout or generation revocation releases backend ownership
immediately, finishes the already-latched byte through the normalized-disconnect phase, and then
resets the wire. Restarting SC abandons that old byte and begins a fresh transfer.

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

Reset command `16`, hang-up `13`, ISP logout `22`, peripheral replacement, ROM/session replacement,
state load/restore, rewind, stop, and controller cancellation disconnect every live backend
operation. State capture/save is
observational and does not cancel the running backend. When external work or a logical connection
exists, however, the captured image removes the backend-reserved packet slot and host-dependent
output, records `externalIoAtCapture`, and substitutes the stable disconnected outcome/error. On
load, current backend ownership is cancelled before that deterministic disconnected image is
installed; no socket or request is restored. Backend completion is queued and at most one result is
applied at a controller safe point; late completion from a cancelled generation is discarded.
Configuration writes validate a detached copy and commit atomically in core memory. A changed write
retains one runtime-only complete image and attachment-local revision; same-value, zero-length, and
rejected packets publish no mutation. The controller pulls that image at the completed-frame safe
point and before endpoint handoff/teardown, then offers it to the private bounded writer. Filesystem
work never runs on the emulator thread. State capture and restore do not serialize or replay this
runtime notification. A later real guest write after state restore publishes the complete restored
image plus that change. Rewind history is cleared at every accepted guest write and as soon as
external ownership is observed, including while the machine is paused, so history cannot bridge
either a durable configuration side effect or an unrepresentable DNS/socket interval. The desktop
reports an informational notice when a capture receives the external-I/O marker. Loading a marked
capture reports the disconnected warning even if the currently attached endpoint is idle; loading
any state while current host work is live reports the same non-restoration boundary.

The stable engine IDs serialized by Phase 1 are append-only:

| Kind | Stable IDs |
|---|---|
| phase | `SLEEP=1`, `SESSION=2`, `TELEPHONE=3`, `INTERNET=4` |
| outcome | `NEED_MORE=1`, `SESSION_STARTED=2`, `SESSION_ENDED=3`, `SESSION_RESET=4`, `CHECKSUM_ERROR=5`, `IDLE_TIMEOUT_RESET=6`, `IDLE_BOUNDARY_WAIT=7`, `CONFIG_READ=8`, `CONFIG_READ_BOUNDARY=9`, `UNSUPPORTED_COMMAND=10`, `MAGIC_ERROR=11`, `RESERVED_ERROR=12`, `LENGTH_LIMIT=13`, `BUFFER_LIMIT=14`, `TIME_REGRESSION=15` (transient), `CANCELLED=16`, `PENDING_LIMIT=17`, `CONFIG_WRITE=18`, `BACKEND_PENDING=19` (never captured), `BACKEND_RESPONSE=20`, `BACKEND_ERROR=21`, `BACKEND_REMOTE_CLOSED=22`, `EXTERNAL_IO_DISCONNECTED=23`, `TELEPHONE_DIALLED=24`, `TELEPHONE_HUNG_UP=25`, `TELEPHONE_STATUS=26`, `ISP_LOGGED_IN=27`, `ISP_LOGGED_OUT=28`, `SERVICE_ERROR=29` |
| error | `NONE=0`, `INVALID_MAGIC=1`, `RESERVED_VALUE=2`, `LENGTH_LIMIT=3`, `CHECKSUM=4`, `UNSUPPORTED_COMMAND=5`, `BUFFER_LIMIT=6`, `TIME_REGRESSION=7` (transient), `PENDING_LIMIT=8`, `BACKEND_BUSY=9`, `BACKEND_UNAVAILABLE=10`, `BACKEND_RESPONSE_INVALID=11`, `EXTERNAL_IO_DISCONNECTED=12` |

The additive endpoint wire phases are likewise stable and append-only:
`RECEIVE_REQUEST=1`, `REQUEST_ACK_DEVICE=2`, `REQUEST_ACK_COMMAND=3`,
`RESPONSE_TURNAROUND=4`, `RESPONSE_WAIT=5`, `RESPONSE_STREAM=6`,
`RESPONSE_ACK_DEVICE=7`, `RESPONSE_ACK_COMMAND=8`, `RESPONSE_READY=9`, and
`NORMALIZED_DISCONNECT=11`. The active-wire record also persists whether a byte transfer is active
and a response retransmission count in `0..4`.
`RESPONSE_PENDING=10` is runtime-only: external ownership captures normalize to a disconnected
engine/endpoint boundary and never serialize that phase. Phase 11 serializes only one already
latched reply byte with empty wire output and a normalized engine result; it resets immediately
after that byte. Restore validates the bit and response cursors, transfer/wait ownership, retry
count, acknowledgement, and response packet against the nested engine state before mutating the
endpoint.

The released 13-component `MobileAdapterEngineState` remains unchanged and represents captures
without external ownership. The additive `MobileAdapterEngineNetworkState` contains those same
fields—phase/outcome/error/device IDs; fixed parser and lengths; configuration; bounded output;
idle/input ownership; and deterministic slot count—plus the final external-I/O-at-capture marker.
Runtime connection kinds, backend request IDs, generations, and host ownership are absent. The
released endpoint state likewise remains unchanged; an additive endpoint-network state pairs the
new engine record with the current serial byte and bit index. A released mid-byte endpoint record
continues its historical `ff` reply, while a new in-flight `d2` request byte uses the additive wire
record, including when the nested engine capture normalizes open external ownership. Any other
external-I/O capture taken mid-byte uses phase 11 to finish only its latched reply before aborting
the old transaction. All records defensively copy arrays. Released Phase-2 backend-error states
with a response and no acknowledgement remain accepted; new active-wire errors retain their
already-sent two-byte request acknowledgement.
Restore accepts both generations, validates the complete graph before mutation, cancels the
injected backend, and never captures that backend.

## Atomic private configuration

Desktop startup loads configuration on the launcher thread, before entering Swing, and supplies a
cached immutable value to the controller. The emulator and EDT therefore perform no filesystem
work. The dedicated file is separate from general preferences. Current variable-length version 2
is `307..640` bytes:

| Offset | Width | Field |
|---:|---:|---|
| 0 | 8 | ASCII magic `CGBMACFG` |
| 8 | 1 | format version `2` |
| 9 | 1 | device ID `0..127` |
| 10 | 256 | private configuration bytes |
| 266 | 1 | known flags; bit 0 means custom-server policy |
| 267 | 4 | literal resolver IPv4 bytes; zero only in offline mode |
| 271 | 2 | big-endian resolver port; zero only in offline mode |
| 273 | 1 | ASCII DNS query-name length `0..253` |
| 274 | n | canonical DNS query name; empty only in offline mode |
| 274+n | 1 | mapping count `0..16` |
| 275+n | 5m | mappings: transport ID, guest port, target port |
| final 32 | 32 | SHA-256 of every preceding byte |

The exact 300-byte version-1 record remains readable and migrates to an offline policy; it retains
its original two-byte configuration-length field and digest layout. Every new save uses version 2.
Runtime network consent and the separate private/local development gate are deliberately absent
from both formats and reset on every application start and every policy edit.

### Guest-authored configuration writes

The adapter's configuration-write command changes the live 256-byte image synchronously at the
documented wire commit point. Coffee GB durably stores only writes that actually change at least one
byte. The controller identifies each endpoint attachment before accepting its writes, drains the old
attachment before replacement ownership commits, and fences a late detached attachment from replacing
the committed one. Bursts retain only their latest complete image; raw bytes never enter the event
bus, status text, logs, exception messages, or diagnostics.

The in-memory image becomes authoritative as soon as the controller accepts it, so reset, policy
refresh, detach/reattach, and ROM replacement construct a new endpoint from the guest's latest
configuration even while disk persistence is pending. Policy saves modify only policy; guest writes
modify only the image. A storage failure does not roll back a command already acknowledged to the
game. It emits only a typed redacted failure and retains the dirty image for retry. Final desktop
close freezes the timing thread, drains the endpoint, and includes this writer in the existing
retryable persistence barrier; failed or timed-out durability retains the session instead of
silently discarding the guest setup.

### Bounded adapter-image import

The retained configuration dialog accepts exactly one owner-selected regular file. A 256-byte
source is an opaque game-visible adapter image. A 512-byte REON/libmobile envelope is accepted only
after validating its `MA` magic and checksum and its `LM` magic, version, checksum, and address-type
fields. Coffee GB then retains only bytes `0..255`. It discards the complete `LM` library region,
including its device, DNS, peer-to-peer, relay, token, and host metadata. Every other length and
every malformed envelope is rejected without partially changing durable configuration.

The reader runs on the bounded configuration writer, never on the emulator thread or Swing EDT. It
uses a 513-byte probe ceiling so an otherwise valid image with trailing data cannot be accepted,
requires a regular file with a non-null provider file key, and disables symbolic-link following for
the selected final path component. Before and after the read it compares the path's type, file key,
exact size, creation time, and modification time with the initial attributes, and compares the
opened channel size with the same exact size. A provider without stable file identity fails closed
as `IMPORT_READ_FAILED`.

Coffee GB does not intentionally change the selected source's permissions or contents or create a
file-level copy. It retains the selected path only while the queued import runs and does not log or
persist that path or include its path or bytes in statuses, exceptions, or diagnostics. It rejects
observed instances of the private `CGBMACFG` target, same-file aliases, and
conservatively case-folded names that could be atomic-write backup or temporary artifacts;
separation is checked before read and again under the store lock before replacement. With a stable
directory entry, the source is left unchanged. The source may contain private dial-up or account
material. On success, only the detached 256-byte result is written through the existing owner-only
atomic store; Coffee GB never persists the selected source as a separate file.

Java 16 has no portable descriptor-bound file type/identity query or nonblocking regular-file open.
The identity/size/timestamp checkpoints fail closed for observed substitutions, but a process that
can rewrite the selected directory can still swap an entry entirely between checkpoints or replace
it with a FIFO during the narrow blocking-open window. Select import input from a private directory
that is not writable by an untrusted process.

Import changes only the game-visible image. It preserves Coffee GB's durable device ID and complete
structured custom-server policy, and it never treats any `LM` field as Coffee GB policy or network
authority. Every owner-triggered import attempt first revokes all prepared and active backends,
clears both runtime-only authorization gates, and requests an endpoint refresh. This applies to
stale, malformed, non-regular, unreadable, conflicting-source, busy, and storage-failing attempts
as well as successful ones. Failure keeps the last accepted runtime image, device ID, and policy,
but authority remains revoked; the persistence caveat below still applies after a completed rename.
Success likewise requires the owner to grant the runtime gates again before any host networking.

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

The configuration view reports whether the launcher selected a validated record, recovered backup,
last-good value, or synthetic fallback, plus the safe device ID, network mode, mapping count, and
stable diagnostic. Its summary never receives the private byte array or renders resolver/name/port
values. The editor may show the user's own owner-only policy while it is open. It supports offline
or custom-server mode, one literal resolver, one canonical DNS name, and exact TCP/UDP guest-to-
target port mappings.

Runtime authorization is fail-closed and independent of event delivery. Every gate or policy edit
holds the coordinator authority lock and directly revokes every prepared backend before publishing
replacement runtime state. A backend's first revocation sets its monotonic flag and rotates its
generation; an already-revoked backend stays unavailable. New offers then fail immediately, port
ownership is cleared, and the owner loop discards DNS capabilities and live sockets. Controller
cancel/refresh events request endpoint handoff and UI presentation, but a failing event subscriber
cannot preserve the old authority.

Saving first retains the previous durable policy with both runtime gates disabled and requests an
immediate endpoint refresh; the owner-only filesystem write then runs on the bounded writer away
from the emulator and EDT. Success makes the candidate policy current, while failure reconciles to
the last durable policy, still with both gates disabled. Every serialized result is reconciled, so
overlapping saves cannot make runtime state lag behind the last successful commit. One write may
run and one may wait; a further request returns `CONFIGURATION_BUSY`. The rejected request also
leaves runtime authority revoked. Both authorization and saving are bound to the revision displayed
when the dialog opened. A stale dialog returns `CONFIGURATION_STALE` without changing authority,
runtime state, durable storage, or controller events. Close first revokes and closes all prepared
backends, then uses the single shared 2,000 ms shutdown deadline described above. Its graceful-then-
interrupt sequence does not extend that attempt's deadline. A later desktop close retry re-awaits
any writer/backend that is still terminating under a fresh bounded deadline. A deadline/interruption
is surfaced as a stable shutdown failure rather than exception detail in Mobile Adapter status.

Outbound networking requires an explicit session-only confirmation. Loopback and RFC1918 targets
also require a second, separately labeled development confirmation; link-local, multicast,
unspecified, documentation, benchmarking, carrier-grade NAT, and other special ranges remain hard
denied even with both confirmations. The UI exposes typed phases, bounded connection counts, cancel,
and stable errors only. Remote names, literal addresses, ports, payloads, exception text, paths,
credentials, dial strings, and tokens do not enter statuses, dialogs, diagnostics, or logs.

## Privacy and endpoint responsibility

Custom-server networking is plaintext outbound I/O, not an anonymity or encryption feature. The
configured DNS resolver receives the configured query name plus the user's source address/port and
timing. The selected custom service receives the user's source address/port, timing, mapped target
port, and the Game Boy transfer payload. Network operators on those paths can observe destination
metadata and unencrypted DNS/TCP/UDP contents. Coffee GB neither adds TLS nor redacts bytes on the
wire; payload redaction applies only to application logs, diagnostics, status events, and dialogs.
Use only a service and network you trust, and do not put credentials, tokens, or other secrets in a
custom-service flow.

The owner-only configuration file necessarily contains the configured resolver, query name, port
mappings, and the adapter's private 256-byte configuration region. Runtime consent flags are never
stored. The configuration editor shows the values entered by its user, but summaries and runtime
status expose only mode, counts, slot IDs, phases, and typed errors. The policy's special-range
classification is not an ownership or brand database: consent for a public destination does not
prove that it is a community server. Coffee GB supplies no Nintendo address and never chooses one
automatically; responsibility for selecting the documented non-Nintendo custom service remains
with the user.

An owner-selected import source must be handled as private account material. Coffee GB neither
modifies the source nor creates or retains a file-level copy, but the copied or extracted 256
game-visible bytes become part of the owner-only configuration record and deterministic save
states. Those outputs require the same private handling and explicit redaction boundaries described
above.

## Troubleshooting

- The current desktop keeps Mobile Adapter controls hidden. The following actions describe the
  retained configuration and link-port UI when a development integration exposes it; the adapter
  image import does not itself change that exposure boundary.
- Select **Peripherals → Link-port device → Mobile Adapter GB**. Status changes to
  attached only after the controller commits the endpoint; selecting another radio item detaches
  and clears it.
- Open the Mobile Adapter configuration action to choose offline or custom-server policy. A saved
  custom policy still performs no host I/O until session consent is granted; private/LAN targets
  require the additional development confirmation. **Cancel active network work** rotates backend
  ownership and closes current DNS/socket work without waiting on the EDT.
- **Import adapter image…** accepts only an exact opaque 256-byte image or a validated exact
  512-byte REON/libmobile envelope. `IMPORT_MALFORMED_IMAGE`, `IMPORT_NON_REGULAR_SOURCE`,
  `IMPORT_READ_FAILED`, and `IMPORT_SOURCE_CONFLICT` are stable redacted failures. The last accepted
  runtime image and policy remain active after a failure, but a failure reported after a completed
  rename may already have replaced the durable target. Every attempted import revokes active work
  and both runtime permissions.
- `CONFIGURATION_INVALID` means a bounded value failed validation. `STORAGE_FAILED` means the
  private record could not be loaded. Coffee GB uses a safe synthetic fallback at desktop startup
  and logs only the stable error code.
- `PORT_OWNED_BY_LINK` means an active linked/netplay controller still owns the serial port. Stop
  that link and retry the selection. If its persistence barrier fails, the radio rolls back and a
  later selection retries the retained linked-controller handoff even if the network toggle has
  already cleared.
- Request packets receive the two-byte acknowledgement before turnaround/poll bytes and the
  response packet. The first post-acknowledgement byte is an unconditional `d2`; the following
  `4b` poll also receives `d2`, and only the next transfer can begin with `99`. A game that still
  waits should first confirm that the custom backend is enabled, runtime consent is granted, and
  its configured DNS/port mappings point to the intended custom service.
- Commands `12`, `13`, `17`, `21`, and `22` emulate only the blue-adapter outbound Crystal state
  transition; they do not place a telephone call, authenticate an account, enable a listener/relay,
  or choose a Nintendo production endpoint.
- `CONSENT_REQUIRED`, `PRIVATE_LOCAL_GATE_REQUIRED`, `DESTINATION_DENIED`, `DNS_FAILED`,
  `DNS_INVALID`, `INVALID_REQUEST`, `INVALID_CONNECTION`, `TIMEOUT`, `CONNECTION_REFUSED`,
  `DESTINATION_UNREACHABLE`, `CONNECTION_LIMIT`, `TRANSFER_LIMIT`, `QUEUE_EXHAUSTED`,
  `REMOTE_CLOSED`, `CANCELLED`, and `IO_FAILED` are coarse privacy-safe categories. They never
  contain the endpoint or backend exception. Connection/transfer phases and slot-scoped failures
  identify only logical slot `0` or `1` and the bounded active count, never a remote endpoint.
- `CONFIGURATION_BUSY` means one policy write is executing and the single pending-write slot is
  already occupied. Wait for its result before saving again; the current durable policy remains
  usable only after the user explicitly grants its runtime gates again.
- `CONFIGURATION_STALE` means the policy changed after the configuration dialog opened. Nothing
  from that stale edit was saved or applied; reopen the dialog to review the current policy.
- Saving during external I/O records a non-restorable-I/O marker but does not interrupt the live
  session. Loading that state, loading another state while I/O is live, rewind, reset, detach, and
  shutdown cancel host ownership; no socket, DNS request, worker deadline, or connection ID is
  serialized or recreated. The desktop reports the saved/disconnected boundary explicitly.
- A user-supplied Japanese Pokémon Crystal ROM confirms wake, BEGIN, telephone-status, END, and the
  adapter-check screen. Deterministic tests cover the complete outbound service sequence without
  committing a ROM. A ROM-generated DNS/TCP/HTTP request remains a manual gate requiring suitable
  gameplay state and an explicitly selected non-Nintendo custom service such as REON.

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
