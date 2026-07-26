# ADR 0001: allocate the next netplay wire generation as v9

- Status: accepted; Phase #347 foundation implemented
- Date: 2026-07-26
- Issues: #346, #318; state boundary #314

## Context

The roadmap text for #318 and #346 calls the future pairing protocol “v7”. That label predates
Coffee GB's shipped protocol-v8 capability handshake and portable StateFile integration. Protocol
v8 is already a frozen public byte contract: its server-first `CoffeeGB NETPLAY` preface, version
byte `08`, four-byte StateFile-v1 capability record, command IDs, record layouts, limits, and
rejection behavior cannot be redefined. Reusing v7 would also make a new peer indistinguishable
from the retired pre-StateFile protocol during the most security-sensitive parse step.

## Decision

The next generation is **protocol v9**. Its normative contract is
[netplay-protocol-v9.md](../netplay-protocol-v9.md). V9 begins with the four ASCII bytes `CGB9` and
uses a fixed 64-byte frame header. The server sends the first v9 frame. Consequently:

- a v9 client that receives v8's first four bytes `Coff` rejects locally before sending anything;
- a v8 client that receives `CGB9` rejects locally as a protocol-name mismatch;
- a v9 server never attempts to parse a v8/v7 byte as a v9 field; and
- there is no downgrade, compatibility probe, dual parser, or fallback on the same connection.

Protocol v8 remains implemented and documented exactly as it was before this ADR. Phase #346 added
no production parser. Phase #347 implements the frozen frame/HELLO/lifecycle foundation beside the
isolated v8 implementation, with an explicit preface decision before interpreting a frame. It is
opt-in and not connected to the current end-user v8 controller path.

## Consequences

V9 can require StateFile v2 and exact profile IDs without consuming an undefined v8 bit or
changing a v8 byte. V8 peers do not interoperate with v9 peers; diagnostics must say which preface
or version was detected and that no downgrade exists. A future incompatible framing change needs
another reviewed ADR and a new protocol major number. Later phases may clarify an ambiguity only
through an explicit contract erratum; they must not silently renumber fields or IDs frozen here.
