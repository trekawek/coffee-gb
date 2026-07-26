# Coffee GB netplay protocol v9

This is the authoritative normative contract for protocol v9. The machine-readable registries in
`controller/src/test/resources/netplay-v9/` are part of this contract and are checked against this
document. RFC 2119 words are normative. Protocol v9 is not implemented in production in Phase
#346; #347 consumes this contract.

Protocol v8 is frozen separately in [netplay-protocol-v8.md](netplay-protocol-v8.md). V9 never
downgrades to, probes, or parses v8/v7 on the same connection. The version decision and the stale
roadmap label are recorded in [ADR 0001](adr/0001-netplay-v9-version.md).

## Primitive rules and fixed header

All integers are unsigned, big-endian, fixed-width values. A receiver MUST convert them to a type
wide enough to represent the complete unsigned domain, then perform checked subtraction,
addition, and multiplication. It MUST validate the fixed header, type-specific encoded and decoded
limits, session aggregate, and queue admission before allocating or reading payload bytes.

Header error precedence is fixed and is evaluated as soon as the decisive byte is retained:
magic/major/minor at byte 6, header length at byte 8, type plus known/reserved flags at byte 12,
then channel, sequence/correlation, raw-length equality, per-message lengths, and aggregate/queue
reservation together at byte 32. Only then may a payload reservation be made or payload bytes be
read. Payload precedence is exact read,
bounded decompression, checksum, message schema, state transition, then queue/event admission.
The first failing check supplies the stable error. No later check may allocate or mutate.

Every frame has this exact 64-byte header:

| Offset | Width | Field | Required value or rule |
|---:|---:|---|---|
| 0 | 4 | magic | ASCII `CGB9` (`43 47 42 39`) |
| 4 | 1 | major | `09` |
| 5 | 1 | minor | `00` |
| 6 | 2 | header length | `0040` (64) |
| 8 | 2 | message type | stable ID from the message table |
| 10 | 2 | flags | only bits `0001`, `0002`, `0004`, `0008` below |
| 12 | 4 | sequence | per-direction sequence, starting at zero |
| 16 | 4 | correlation | zero for requests/events; request sequence for responses |
| 20 | 4 | encoded payload length | bytes following this header |
| 24 | 4 | decoded payload length | bytes after optional DEFLATE |
| 28 | 4 | channel | `0` control, `1..4` player slots, `ffffffff` atomic group |
| 32 | 32 | payload digest | SHA-256 of the decoded payload; SHA-256(empty) for no payload |

The four header flags are `OPTIONAL=0001`, `DEFLATE=0002`, `RESPONSE=0004`, and
`TERMINAL=0008`. All other bits are reserved and MUST be zero. A known message MUST use only the
allowed/required flags in `messages.tsv`. An unknown type is skippable only when flags are exactly
`OPTIONAL`, both lengths are equal and at most 4,096, channel and correlation are zero, and its
digest verifies. Any unknown required type, required flag, or invalid flag combination terminates
the session. Unknown optional frames are legal only after both HELLO records have been accepted,
in `SYNCHRONIZING` or `ACTIVE`; they consume sequence numbers but never change session state.

Raw DEFLATE (`nowrap=true`, RFC 1951) is allowed only where `messages.tsv` says `raw-deflate` and
only after capability 10 is negotiated. There is no preset dictionary or concatenated stream.
The inflater MUST produce exactly the declared decoded length, finish exactly at encoded EOF, and
have zero unused/trailing bytes. Truncation, corrupt blocks, dictionary requests, expansion past
the declaration or message limit, decoded underflow, and trailing compressed bytes fail before
delivery. A checkpoint is already a StateFile and MUST NOT use outer DEFLATE.

TCP is a byte stream. A receiver MUST accept every fragmentation, including one byte per read, and
any coalescing of complete frames. It MUST NOT treat a short socket read as a frame boundary. EOF
with a partial header/payload is `TRUNCATED`; EOF before a terminal exchange is `UNEXPECTED_EOF`.
Bytes after a terminal frame are `TRAILING_DATA`, not another session.

## Sequence, response, and cleanup rules

Each direction starts with HELLO sequence `0`; every subsequent frame increments by exactly one.
Duplicates, gaps, reordering, and wrap are `SEQUENCE_ERROR`. Sequence `fffffffe` is the last
usable value. After accepting or sending it, that direction is exhausted and MUST close without
emitting `ffffffff`; `ffffffff` is never a frame sequence. Non-response frames have correlation
zero. A `RESPONSE` has a nonzero correlation naming exactly one outstanding peer request and that
request may receive at most one response. `AUTH_RESULT` responds only to AUTH, READY only to
START, PONG only to PING, and ERROR to the named offending request when applicable. A wrong
response type, wrong correlation, unsolicited response, duplicate response, or response to a
completed request is `CORRELATION_ERROR`.

ERROR, CANCEL, and GOODBYE are terminal. After sending one, the sender half-closes output, stops
queue admission, cancels jobs, and waits at most 2,000 ms for peer EOF before closing the socket.
After receiving one, the receiver admits no additional frame, cancels jobs, closes bounded queues,
releases the slot, and closes. An I/O error, timeout, invalid frame, or cancellation follows the
same cleanup path. Cleanup failure is logged without tokens, paths, ROM/save bytes, payload
fragments, or credentials and cannot poison the listener/accept loop or another session.

## Message registry and payload schemas

`messages.tsv` is the numeric registry and per-frame limit table. `fields.tsv` is the exhaustive
offset/width/rule registry for the 64-byte header and every payload/entry schema named below. IDs
are permanent. No Java or Kotlin enum ordinal, display name, or class name is a wire identity.

| ID | Name | Decoded payload and rule |
|---:|---|---|
| `0001` | HELLO | exact schema below; no compression |
| `0002` | AUTH | invitation proof; exact 36 bytes |
| `0003` | AUTH_RESULT | status and reserved word; exact 4 bytes |
| `0004` | MANIFEST | bounded roster, compatibility diff, and item proposal metadata |
| `0005` | CONSENT | one directional, item-scoped decision; exact 116 bytes |
| `0006` | START | session ID and initial frame; exact 16 bytes |
| `0007` | READY | accepted session ID; exact 8 bytes |
| `0008` | INPUT | frame/player/button data; exact 16 bytes |
| `0009` | CHECKPOINT | one direct StateFile v2 and group metadata |
| `000a` | ROM_BEGIN | consent-gated ROM transaction declaration |
| `000b` | ROM_CHUNK | bounded ROM transaction data |
| `000c` | ROM_END | ROM transaction digest and commit |
| `000d` | BATTERY_BEGIN | consent-gated battery transaction declaration |
| `000e` | BATTERY_CHUNK | bounded battery transaction data |
| `000f` | BATTERY_END | battery transaction digest and commit |
| `0010` | RESET | frame/player and zero reserved bytes; exact 16 bytes |
| `0011` | STOP | frame/player and zero reserved bytes; exact 16 bytes |
| `0012/0013` | PING/PONG | opaque nonce and diagnostic monotonic stamp; exact 16 bytes |
| `0014` | CANCEL | stable reason plus bounded diagnostic text |
| `0015` | GOODBYE | stable reason plus bounded diagnostic text |
| `0016` | ERROR | stable error/type/sequence plus bounded diagnostic text |

### HELLO and capabilities

HELLO payload is: role (`1` server, `2` client), minimum major (`9`), maximum major (`9`), zero
reserved byte, 32 random nonce bytes, unsigned 16-bit capability count, then that many 8-byte
records. Each record is capability ID u16, capability schema version u16 (all current values are
1), and flags u32 (`bit 0 = required`, all other bits zero). Records are strictly increasing by ID,
unique, and count is at most 32. All seven required capabilities in `capabilities.tsv` MUST appear
as required in both HELLOs. Unknown required capabilities fail; bounded unknown optional
capabilities are ignored. A known capability with wrong version/requiredness fails. Link mode four
requires capability 11. ROM messages require capability 8; battery messages require capability 9;
a chunk with DEFLATE additionally requires capability 10; and PING/PONG require capability 12.
A gated message is illegal even when its frame shape is otherwise valid.

`capabilities.tsv` is the stable numeric capability registry:

| ID | Name | Required |
|---:|---|:---:|
| `0001` | FRAME_V1 | yes |
| `0002` | INVITATION_PROOF_V1 | yes |
| `0003` | MANIFEST_V1 | yes |
| `0004` | CONSENT_V1 | yes |
| `0005` | STATEFILE_V2 | yes |
| `0006` | PROFILE_ID_ASCII_V1 | yes |
| `0007` | ATOMIC_GROUP_CHECKPOINT_V1 | yes |
| `0008` | ROM_TRANSFER_V1 | no |
| `0009` | BATTERY_TRANSFER_V1 | no |
| `000a` | RAW_DEFLATE_V1 | no |
| `000b` | FOUR_PLAYER_V1 | no |
| `000c` | PING_V1 | no |

The server writes its complete HELLO first. A v9 client reads and validates it before writing any
byte. The client then writes HELLO. A detected prefix other than `CGB9` produces local
`UNSUPPORTED_PROTOCOL` diagnostics (including `Coff` as v8 when known) and no response on that
connection. Thus no v8/v7 byte is consumed as a v9 field and no downgrade oracle exists.

### Invitation authentication

Player `0` is always the host. Normal mode authenticates exactly guest player `1`; four-player
mode authenticates guests `1..3` on three independent TCP sessions. AUTH is that player/slot u8,
three zero reserved bytes, then
`HMAC-SHA-256(token, "CoffeeGB-v9" || serverNonce || clientNonce || slot)`. The ASCII label has no
terminator. The raw 16-byte invitation token is never sent. Proof comparison uses a constant-time
byte comparison. AUTH_RESULT is status u16 (`0` accepted, `1` rejected) and zero u16. Rejected
results use `RESPONSE|TERMINAL`; all wrong, expired, used, wrong-slot, malformed-proof, and unknown
tokens are peer-visible only as generic `AUTH_FAILED`. Rate-limit metadata is local only.

### MANIFEST, differences, and item proposals

MANIFEST is `342..1,396` bytes. Its fixed 52-byte header contains schema `1`, mode (`1` normal,
`2` four), sender player, `2..4` roster entry count, at most eight transfer proposals, at most
sixteen differences, zero reserved bytes, protocol major `9`, StateFile version `2`, application
compatibility level `1`, build/core compatibility level `1`, roster mask, nonzero roster
generation, and a SHA-256 roster commitment. The roster always contains host player 0 and the
authenticated guest; normal is exactly mask `03`. A four-player host coordinates three per-guest
TCP sessions for players 1, 2, and 3. Every per-guest manifest covers the same committed roster,
and START is withheld until slot occupancy, every required item decision/transfer, and the one
atomic group checkpoint are ready on all participating sessions.

Entries are strictly player-sorted and exactly `144 + profileLength + titleLength` bytes. Each
contains player, content flags (primary-ROM present, slot-ROM present, battery available),
bootstrap and accessory flags, profile/title lengths, raw cartridge-header type, stable mapper
family ID, primary/slot sizes, primary/slot/boot/patch SHA-256 values, canonical lowercase profile
ID, and a sanitized printable-ASCII internal cartridge title of at most 16 bytes. Paths, save
bytes, usernames, device identifiers, arbitrary metadata, and ROM bytes remain forbidden. A ROM
may be absent or differ. This is a warning requiring explicit approval, never an implicit transfer.
Own-ROM exact match is the default and requires no ROM proposal.

Stable mapper family IDs are: `ROM_ONLY`, `MBC1`, `MBC2`, `MBC3`, `MBC5`, `MBC6`, `MBC7`,
`MMM01`, `CAMERA`, `HUC1`, `HUC3`, `TAMA5`, `M161`, `DATEL`, `UNLICENSED`, and
`UNKNOWN_KNOWN_HEADER`. `mapper-families.tsv` assigns their permanent numeric values.

After the entries come fixed 12-byte difference records and fixed 48-byte transfer proposals.
Stable difference IDs are `PROTOCOL_CONTEXT`, `STATE_CONTEXT`, `PROFILE_IDENTITY`,
`ROSTER_IDENTITY`, `PRIMARY_ROM_MISSING`, `PRIMARY_ROM_DIFFERENT`, `SLOT_ROM_MISSING`,
`SLOT_ROM_DIFFERENT`, `BATTERY_OPTIONAL`, and `MATCH`. Severity is exactly Fatal,
Warning-requiring-approval, or Informational. Fatal differences cannot be approved. A warning
names one nonzero proposal ID. A proposal binds action (offer by source or request by target),
class, asset kind, owner player, source player, distinct target player, exact expected size and
SHA-256 (or the explicitly zero checkpoint sentinel), and the warning disposition. Proposals are
unique and directionally authored; class and asset kind must agree.

Application/build levels plus protocol/StateFile capability context are compared before content.
The two stable numeric compatibility levels deliberately replace a free-form build/version string:
they let implementations declare behavioral compatibility without leaking a detailed build
fingerprint, and changing either level is Fatal.
Profile/bootstrap/accessory/boot/patch and roster differences are fatal. Missing/different primary
or slot ROM is a warning only when an explicit advanced offer/request proposal exists. Battery
availability is informational unless a distinct battery proposal is made. The complete decision
registry and executable outcomes are in `manifest-diffs.tsv` and
`manifest-consent-vectors.tsv`.

### CONSENT and payload classes

CONSENT is an exact 116-byte item decision. It binds a nonzero decision ID, actor, approve/reject,
class (ROM, battery, or checkpoint), asset kind, source, target, owner, proposal ID, expected size
and SHA-256, plus the SHA-256 of the exact server and client MANIFEST payloads. Both the proposal
source and target must submit one matching approval; offer/availability and permission to receive
are separate facts. Rejection is final. A manifest change, replayed decision, duplicate actor,
wrong direction/player/asset/class, extra transaction, or mismatched identity rejects.

No ROM, battery/save, StateFile, path, or other private/large content may be read for transfer,
compressed, queued, announced in content form, or sent before AUTH, both exact manifests, and both
item approvals have succeeded. Every BEGIN or CHECKPOINT names the approved proposal. Consent for
ROM never authorizes battery or checkpoint, and one proposal authorizes at most one transaction.
A UI cancel is a denial. START remains illegal until every approved transfer has completed and all
candidate sessions are prepared without live mutation.

### Runtime payloads

START is nonzero session ID u64 and initial frame u64. READY is its one correlated response and
repeats that session ID. INPUT is frame u64, player u8, stable button mask u8, intra-frame order
u16, and zero u32. RESET and STOP are frame u64, player u8, and seven zero bytes. For every
player-bearing frame, channel is exactly `player + 1`; group channel remains `ffffffff`.
PING/PONG are opaque nonce u64 and diagnostic monotonic-microsecond
stamp u64; the stamp never drives emulation.

CHECKPOINT is checkpoint kind u8 (`0` initial MACHINE, `1` normal SESSION, `2` four-player
LINKED_SESSION), player mask u8, owner player u8, zero u8, frame u64, StateFile byte length u32,
nonzero approved proposal ID u32, then exactly that many direct StateFile bytes. It is channel
`ffffffff`, except initial MACHINE may use channel `owner+1`. File length must equal the remaining
decoded payload and be at
least the 68-byte StateFile envelope. The first bytes are `CGBS`, StateFile format is exactly v2,
and envelope/root/profile/ROM/slot/accessory integrity is validated before graph reconstruction.
No outer compression is allowed.

ROM_BEGIN and BATTERY_BEGIN are 52 bytes: transaction ID u32, approved proposal ID u32, source,
target, owner, asset kind u8 values, total decoded length u32, SHA-256 of the complete decoded
payload, and chunk size u32 (1..65,536). ROM asset kind distinguishes primary from slot ROM.
Channel is exactly `owner+1`; source/target/direction/class/size/digest must match item consent.
CHUNK is
transaction ID u32, absolute offset u32, and 1..65,536 data bytes. Chunks are contiguous, ordered,
non-overlapping, and their checked cumulative total cannot exceed the BEGIN total or class/queue
limit. END is transaction ID u32 and the complete SHA-256: 36 bytes. Only one bulk transaction per
session is open. Temporary retention is bounded; completion digest verifies before delivery.
ROM and battery transactions are legal only in `SYNCHRONIZING`; a later content/configuration
change starts a new authenticated session rather than smuggling new content into `ACTIVE`.

CANCEL/GOODBYE payload is reason u16 (zero for a normal goodbye, otherwise a stable
`errors.tsv` code), strict UTF-8 byte length u16 (0..256), then exact text.
ERROR is error code u16, offending type u16, offending sequence u32, strict UTF-8 length u16,
zero u16, and exact text (0..512). UTF-8 must be shortest-form, scalar-valid, and contain no NUL,
control, path, token, or payload data. Text is diagnostic only; stable numeric codes drive logic.

## State machines and timeouts

The exhaustive role/direction transition table is `transitions.tsv`. In short, the only successful
order is:

```text
server HELLO -> client HELLO -> client AUTH -> server AUTH_RESULT
-> server MANIFEST -> client MANIFEST -> server CONSENT -> client CONSENT
-> SYNCHRONIZING (consented transfer/checkpoint preparation)
-> server START -> client READY -> ACTIVE
```

Any other message is `UNEXPECTED_MESSAGE` and terminates. In `SYNCHRONIZING`, only consented
checkpoint and bulk transactions plus terminal frames are legal. In ACTIVE, input/reset/stop,
checkpoint, ping/pong, and terminal frames are legal. A terminal frame transitions
to CLOSED after cleanup. State transitions occur only after the complete frame, checksum,
decompression, payload schema, current-state legality, limits, and queue reservation have passed.
A rejected frame produces **no state transition, allocation based on its declaration, payload
read beyond the decisive boundary, event delivery, emulator mutation, or queue admission**.

Every concrete nonterminal state has the exact monotonic deadline in `timeouts.tsv`; send and wait
states are separate rows so a blocked writer cannot escape a stage deadline. The values are 5 s
for each preface/HELLO/auth network step, 10 s for manifests, 120 s for each local/remote consent
decision, 15 s for start and bulk progress, 120 s for complete synchronization, 30 s active idle
with PING allowed, and 2 s terminal cleanup. A deadline expires when `now >= deadline`. Deadlines
are absolute monotonic values; trickled bytes do not extend them. A fully validated bulk frame may
establish the next `BULK_PROGRESS` deadline but partial bytes may not. Cancellation interrupts
reads/writes/jobs, closes queues and sockets, releases invitation/slot ownership, and cannot block
the emulator thread, EDT, accept loop, or another session.

Listener limits are 8 pending handshakes and 4 handshake workers. One session retains at most 256
queued frames, 33,817,172 queued **wire bytes**, 128 MiB decoded aggregate, four 65,536-byte bulk
chunks, and one checkpoint transaction. Wire bytes count every retained frame's 64-byte header
plus its encoded payload, including checkpoint/bulk metadata and compression overhead. The bound
is intentionally the exact simultaneous admission of one maximum checkpoint frame
(`64 + 33,554,452 = 33,554,516`) and four maximum encoded chunk frames
(`4 * (64 + 65,600) = 262,656`). Their sum is 33,817,172; one more wire byte rejects. Queue
reservation uses checked arithmetic before copying.
Overflow rejects only that session and makes its slot replaceable within the 2 s cleanup deadline.

If all advertised slots are already occupied before HELLO, the server may send one sequence-0
terminal `SERVER_FULL` ERROR instead of HELLO. If the bounded pending/worker admission is full, it
may analogously send `SERVER_BUSY`. The client accepts either only on this pre-HELLO path. If a
specifically authenticated slot becomes occupied after AUTH but before commit, the server sends
only correlated `SERVER_FULL|RESPONSE|TERMINAL`; `SERVER_BUSY` is not legal there. Other failures are
not mislabeled as authentication failure. The rejection writer is separately bounded and cannot
wait on or poison the accept loop; failure to deliver the best-effort error still closes only that
candidate.

## Stable errors

`errors.tsv` is exhaustive. Peer-visible numeric codes are stable; messages are sanitized and may
change. Authentication always uses `AUTH_FAILED`, regardless of expired/used/wrong token or slot.
`SERVER_FULL` and `SERVER_BUSY` remain distinct. Unsupported protocol is normally local because
the peer prefix is not parsed as v9; if both sides parsed v9 and later disagree, ERROR may carry
`UNSUPPORTED_PROTOCOL`.

| ID | Stable name | ID | Stable name |
|---:|---|---:|---|
| `0001` | MALFORMED_HEADER | `0010` | SERVER_FULL |
| `0002` | UNSUPPORTED_PROTOCOL | `0011` | SERVER_BUSY |
| `0003` | UNKNOWN_REQUIRED_TYPE | `0012` | CAPABILITY_MISMATCH |
| `0004` | UNKNOWN_REQUIRED_CAPABILITY | `0013` | MANIFEST_MISMATCH |
| `0005` | UNKNOWN_REQUIRED_FLAG | `0014` | CONSENT_REJECTED |
| `0006` | LIMIT_EXCEEDED | `0015` | ROM_MISMATCH |
| `0007` | TRUNCATED | `0016` | PROFILE_MISMATCH |
| `0008` | CHECKSUM_MISMATCH | `0017` | STATEFILE_VERSION |
| `0009` | DECOMPRESSION_FAILED | `0018` | ROOT_KIND_MISMATCH |
| `000a` | UNEXPECTED_MESSAGE | `0019` | TOPOLOGY_MISMATCH |
| `000b` | SEQUENCE_ERROR | `001a` | QUEUE_OVERFLOW |
| `000c` | CORRELATION_ERROR | `001b` | UNEXPECTED_EOF |
| `000d` | TIMEOUT | `001c` | TRAILING_DATA |
| `000e` | CANCELLED | `001d` | STRICT_UTF8 |
| `000f` | AUTH_FAILED | `001e` | INTERNAL_ERROR |
| `001f` | STATEFILE_MALFORMED |  |  |

## StateFile v2, topology, and atomic application

V9 network state uses only the merged #314 detached model and **StateFile v2**. Java native
serialization, `ObjectInputStream`, local legacy import, `Memento`, `CGBN`, and StateFile v1 are
forbidden. Network limits are tighter than portable-file limits: one direct StateFile is at most
32 MiB encoded and 32 MiB decoded; the whole decoded session aggregate is 128 MiB. Both layers'
graph/value/depth/section/string/array limits still apply.

An initial state is MACHINE. A normal running checkpoint is SESSION. A four-player checkpoint is
one LINKED_SESSION root containing exactly the active slot mask, endpoint identities, one coherent
shared FourPlayerAdapter topology, held inputs, frame/runtime floor, and canonical profile IDs for
all members. It is decoded and target-validated off the emulator thread, then prepared and applied
at the existing frame safe point as one transaction. ROM/slot hashes, profiles, bootstrap/accessory
flags, root kind, slots, local owner, endpoint/topology, and integrity must agree before mutation.
Any member failure rejects only the source and leaves live sessions, history, inputs, frame,
configuration, and topology unchanged. Rollback generation/rebase details are implemented in #349
but may not weaken this grouping or atomicity contract.

## Invitation URI and threat model

An invitation contains 16 bytes generated by `SecureRandom` (128 bits), represented once as
unpadded canonical base64url (exactly 22 characters). The token exists only in bounded memory,
expires 300 seconds after creation (configurable only from 60 through 600 seconds), is bound to one
mode/slot, and is consumed atomically by the first successful AUTH. It is never logged, persisted,
included in diagnostics/telemetry/crash reports, copied to a title, or retained after expiry/use.
Comparisons are constant-time. Wrong, used, expired, and wrong-slot proofs are generic failures.
The host stores an absolute monotonic expiry and rejects when `now >= expiry`; URI `EXP` is the
canonical UTC-seconds display value and is never trusted instead of the host deadline. At most
eight failed AUTH attempts are admitted per listening session in one 60-second monotonic window;
later attempts are the same generic failure until the window rolls. A successful use invalidates
the invitation immediately. Stopping the host invalidates all outstanding invitations.

Canonical URI grammar is:

```text
coffeegb://HOST:PORT/join?v=9&mode=MODE&slot=SLOT&exp=EXP&token=TOKEN
```

- Total ASCII length is at most 512; controls, whitespace, percent encoding, user-info, fragments,
  Unicode/IDNA, and alternate schemes/paths are forbidden.
- DNS is lowercase ASCII, no trailing dot, at most 253 bytes, labels 1..63, alphanumeric edges and
  alphanumeric/hyphen interiors. IPv4 is canonical dotted decimal without leading zeros. IPv6 is
  RFC 5952 lowercase shortest form over eight hexadecimal groups, bracketed, without a zone ID;
  embedded dotted-decimal IPv4 is not an admitted spelling.
- Port is canonical decimal `1..65535` without leading zeros. Query names occur exactly once and
  in the shown order; unknown, missing, duplicate, empty, or reordered fields fail.
- MODE is `normal` or `four`. SLOT is canonical decimal `1` for normal and `1..3` for four. EXP is
  canonical unsigned decimal `1..253402300799`, without a leading zero. TOKEN is exactly 22
  base64url characters `[A-Za-z0-9_-]`, decodes to 16 bytes, and contains no padding.
- Parsing is strict: a noncanonical spelling is rejected, never normalized. Rendering always emits
  this form. `invitation-vectors.tsv` freezes success and typed failure behavior.

V9 has no tokenless/manual-address authentication path. Adding nonce comparison, discovery, or a
different invitation mechanism requires a separately reviewed capability and threat-model change;
it cannot bypass AUTH/MANIFEST/CONSENT in this wire generation.

Invitation parsing is local and uses stable reason names (not wire ERROR numbers): `INV_TOO_LONG`,
`INV_CONTROL`, `INV_ENCODING`, `INV_FRAGMENT`, `INV_SCHEME`, `INV_PATH`, `INV_AUTHORITY`,
`INV_HOST`, `INV_PORT`, `INV_DUPLICATE`, `INV_UNKNOWN`, `INV_MISSING`, `INV_QUERY_ORDER`,
`INV_VERSION`, `INV_MODE`, `INV_SLOT`, `INV_EXPIRY`, and `INV_TOKEN`. The ordered vector corpus
freezes precedence when one string violates more than one rule. User text is sanitized separately.

Invitation possession authenticates only possession of that one invitation. Protocol v9 runs over
plaintext TCP: it provides **no encryption, confidentiality, forward secrecy, server identity, or
protection against an on-path attacker**. It is not a claim of safe Internet exposure. This phase
does not provide TLS, matchmaking, rendezvous, NAT traversal, relay, or public inbound service.

## Phase consumption matrix

| Phase | Consumes this frozen artifact | Production behavior still blocked before it |
|---|---|---|
| #347 | v9 header/registries/state machines and hostile wire corpus | no v9 listener/parser/lifecycle exists in #346 |
| #348 | invitation/auth vectors, AUTH/MANIFEST/CONSENT ordering, ROM/battery transactions, privacy limits | no pairing, transfer, or consent UI exists in #346 |
| #349 | CHECKPOINT schema, StateFile-v2 identity, atomic group/history rules | no v9 checkpoint/rollback integration exists in #346 |
| #350 | cancellation/cleanup/diagnostic contracts and hostile lifecycle cases | no hardening rollout or v8 retirement occurs in #346 |
| #351 | Mobile clean-room ADR/source inventory/transcripts | no production Mobile engine exists in #346 |
| #352 | bounded async backend/status/cancellation contract | no DNS/socket/backend work exists in #346 |
| #353 | desktop adapter/configuration/privacy documentation | no Mobile UI or external-service preset exists in #346 |

Changing a stable field, ID, limit, timeout, transition, invitation grammar, or transcript requires
a reviewed ADR and, where bytes become incompatible, a new protocol/schema version. Later phases
consume these artifacts; they do not silently rewrite them.
