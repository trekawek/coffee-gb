# Swing UI and Java Sound performance analysis

Analysis of the frame pacing, audio output and display paths (branch
`claude-ui-optimization`). All numbers measured on this machine with JDK 21,
`UiBench`-style microbenchmarks plus a real-emulator pacing run (300 frames of
Beetlejuice on the DMG core).

## Findings

### 1. Frame pacing (`TimingTicker`) — a full core burned on waiting

The ticker paced frames with a pure busy-wait:

```kotlin
while (System.nanoTime() - lastSleep < FRAME_DURATION_NANOS) {}
```

Emulating one frame costs a few milliseconds; the remaining ~13 ms of each
60 Hz period were spent spinning. The emulator thread therefore always consumed
**100 % of a core** regardless of how cheap the emulation was. The deadline was
also re-read from `System.nanoTime()` *after* the spin, so scheduling jitter
accumulated instead of averaging out.

**Fix:** absolute deadlines (`deadline += FRAME_DURATION`), sleep the bulk of
the wait with `LockSupport.parkNanos` and busy-spin only the last 1.5 ms for
frame-exact wakeup (`Thread.onSpinWait` in the hot loop). If the emulator falls
more than a frame behind, the deadline re-anchors instead of fast-forwarding.

Measured (2 s synthetic run, 3 ms of work per frame): thread CPU 2000 ms →
530 ms. Real emulator run: **exactly 60.00 fps, thread CPU 100 % → 41 %** of a
core (of which ~32 % is the emulation itself and ~9 % the 1.5 ms spin window).

### 2. Audio output (`AudioSystemSound`) — distortion, drift and garbage

The old implementation had four independent problems:

1. **Sample overflow distortion.** The core mixer outputs up to
   4 channels × 15 × volume 7 = **420** per side; the value was cast straight
   to `byte` for an 8-bit unsigned line, wrapping anything above 255. Loud
   passages distorted harshly.
2. **Clock drift.** Decimation used the integer divider 4194304/44100 = 95,
   producing an effective 44150.6 Hz into a 44100 Hz line — 0.11 % fast, i.e.
   **69 ms of queue growth per minute**. The 10-frame queue filled in ~2.5
   minutes, after which latency sat at 166 ms and every additional frame was
   dropped with a "Buffer overflow" log — audible periodic glitching that got
   worse the longer the emulator ran.
3. **Allocation churn.** The handler cloned the core's per-tick sample buffer
   (549 KB) 60 times a second — 33 MB/s of garbage — although the event bus
   dispatches synchronously and the buffer can be read in place.
4. **Underrun pops.** Any 1 ms gap in production wrote a full frame of silence
   into the line, and `stopThread()` busy-waited on a flag.

**Fix:** rewritten output path.

- Decimation happens inline in the event handler with a **fractional step**
  (`pos += 4194304.0/44100`), carrying the remainder across frames: effective
  rate 44100.009 Hz — no drift, constant latency.
- Output is **16-bit signed PCM** scaled from the mixer range (×76), no wrap.
- The handler emits one small (~2.9 KB) frame buffer; the queue holds at most
  3 frames (drop-oldest), bounding latency at ~50 ms + 45 ms line buffer.
- Silence is only written when the line has actually drained (idle/pause),
  not on transient scheduling gaps; `stopThread` waits properly.

Measured: per-frame handler cost 207 µs + 549 KB clone → **1 µs, no large
allocations**.

### 3. Display (`SwingDisplay`) — per-pixel conversions

Each frame was uploaded with `BufferedImage.setRGB(...)`, which routes every
pixel through the color model of the screen-compatible image, and called
`validate()` (a layout pass) per frame.

**Fix:** the image is a `TYPE_INT_RGB` `BufferedImage` whose raster int array
is written directly with `System.arraycopy` (the emulator already produces
packed RGB ints); `validate()` dropped; explicit `NEAREST_NEIGHBOR` +
`RENDER_SPEED` hints on the scaling blit (also keeps pixels crisp).

Measured frame upload: 160×144 158 µs → 1 µs; 256×224 (SGB) 450 µs → 5 µs.

## 4. Networking (netplay link)

The TCP link exchanging inputs between linked emulators had latency,
correctness and bandwidth problems:

1. **Nagle's algorithm was on** (no `TCP_NODELAY`). The link sends tiny input
   packets whose delivery time directly affects the emulation sync; Nagle plus
   delayed ACKs can hold them back for tens of milliseconds. Both socket ends
   now set `tcpNoDelay` (and `keepAlive`, so a vanished peer eventually fails
   the blocking read instead of hanging forever).
2. **Byte-wise unbuffered I/O.** Each message was written as a series of small
   `write()` calls on the raw socket stream (the command byte alone, then the
   header, then one call per button) - a syscall and potentially a TCP segment
   each. Messages are now assembled in memory and leave in a single
   write+flush. Reads go through a buffered stream.
3. **Short-read protocol desync.** Header reads used `InputStream.read(buf)`
   without checking the returned count; on a fragmented connection (any real
   WAN) a partial read permanently desynchronized the protocol. All reads now
   use `readFully`.
4. **Unsynchronized writers.** Different event-bus threads could interleave
   two messages mid-stream. Message sending is now synchronized and atomic.
5. **Raw payloads.** The ROM (up to 8 MB), battery save and the Java-serialized
   snapshot went uncompressed; they are now deflated (a 256 KB test image
   compresses ~8x, sparse memento-like data 4-20x), protocol version bumped
   to 2.
6. **Handshake ordering hazard** (pre-existing): send handlers were registered
   at construction but the handshake was written only when `run()` started, so
   an event arriving in between put its bytes in front of the handshake and
   desynchronized the peer. The handshake now completes inside the
   constructor, before any handler can write.
7. **Reverse DNS on the accept path**: the server posted
   `inetAddress.hostName`, a blocking reverse lookup that delayed the
   handshake by ~10 s on hosts without a resolver. It posts the numeric
   address now.

Coverage: `ConnectionTest` (framing under 1-byte-fragmented delivery,
compression round-trip, concurrent senders, handshake rejection, truncated
payloads) and `TcpConnectionTest` (end-to-end exchange over real loopback
sockets through TcpServer/TcpClient). Items 3, 6 and 7 were found by these
tests.

## Not changed (considered and rejected)

- **Core `Sound` buffer shape** (per-tick samples posted once per frame): part
  of the accuracy-verified core; consumers can decimate cheaply, so the shape
  stays.
- **VolatileImage / managed images** for the display: the direct-raster write
  makes the image unmanaged, so the scale blit runs in software — at GB
  resolutions (≤ 256×224 source) this costs well under a millisecond and is
  simpler than a VolatileImage restore-loop. Revisit only if 8×+ scaling on a
  weak GPU shows up in profiles.
- **Lower spin threshold** than 1.5 ms in the ticker: `parkNanos` slack varies
  by OS; 1.5 ms is safe everywhere and costs ~9 % of one core.
