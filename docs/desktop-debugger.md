# Desktop debugger

Open a specific modeless debugger window from **Debug > Execution**, **Memory**, **Breakpoints**,
**Video**, **Hardware & I/O**, **Audio**, or **Timeline**. Built-in arrangements are under
**Debug > Layout**. The debugger windows have no menu bars of their own and can remain open while
the game runs. They never call the emulator core directly: every command goes through the current
session's `DebugPort` and is applied at an emulation safe point. A linked/rollback session may
publish an inspection-only or unsupported port; each window reports the negotiated capabilities
instead of offering unsafe one-machine controls.

## Snapshot identity and panes

The header identifies every view as `Session …, snapshot …, tick …`. Registers, machine state,
stack, disassembly, requested memory, peripheral data, and a trace page returned by one refresh all
come from that same coherent safe point. A run-control or CPU-only refresh immediately releases
older Graphics and Audio rows and labels those panes as not captured for the new identity; they do
not remain visible under a newer header while their selected-pane refresh is prepared.

Inspection sections are limited to visible, non-held tools. Video adds cadence-gated Graphics;
Audio adds Audio; Hardware & I/O adds Hardware, Audio, and cadence-gated Graphics. Detached payloads
are decoded on one bounded worker and only payload-free table rows reach Swing's event thread.
Changing session, hiding, or closing the debugger cancels that work and releases its snapshots and
raw bytes.

| Pane | Contents |
| --- | --- |
| **CPU** | Registers and flags, execution/interrupt/timer/PPU/APU scalars, mapper banks, stack, and side-effect-free disassembly. |
| **Memory** | Live side-effect-free ROM, work-RAM, or high-RAM ranges. While paused, one safe RAM/HRAM byte may be edited at a time. |
| **Breakpoints** | Searchable, sortable Breakpoint Center for PC, memory, opcode, interrupt, PPU, serial, master-tick, and frame conditions. Definitions can be added, edited, duplicated, enabled/disabled, or removed without blocking the UI. |
| **Graphics** | Hardware mode, VRAM tile banks, background/window maps, all 40 OAM objects, DMG registers, and CGB RGB555 palettes. |
| **Hardware & I/O** | Semantic owner-captured joypad, serial/IR, DMA/HDMA, banking, and system state with explicit unavailable/unknown provenance. |
| **Audio** | Mixer/frame-sequencer state, four APU channels, raw/decoded registers, all 32 wave samples, and output-only channel enable/mute controls. |
| **Timeline** | Sequence-ordered typed events, including serial and input activity when those categories are selected. |

Address fields accept hexadecimal forms such as `$C000`, `0xC000`, or `C000`; ranges use a hyphen.
Invalid, side-effectful, oversized, or unsupported requests are reported in the status line. A
successful memory edit requires an application/user or debugger pause, writes one safe RAM/HRAM byte
without a bus side effect, and clears rewind/reverse history plus timeline correlation because the
input-only history cannot reconstruct it. Audio channel controls are output-only, work while running, do not
change emulated APU registers, and do not invalidate history. Table and color-preview information
is also available as text.

## Run control and keyboard access

- **F5** refreshes the coherent view.
- **F6** pauses at the next safe retirement or releases the debugger-owned pause.
- **F7** steps one instruction; **Shift+F7** steps one frame.
- **F8** moves back one recorded instruction; **Shift+F8** moves back one recorded frame.
- **F9** toggles an exact program-counter breakpoint at the current paused address. If imported or
  external state contains duplicates at that address, the toggle removes all of them.
- The platform menu shortcut plus `+`/`-` changes font size, `0` resets it, and `C` copies the
  current pane or selected table rows.

Toolbar controls have keyboard mnemonics. The Breakpoint Center uses unambiguous Enter, F9, and
platform-shortcut+F actions plus normal focus traversal, rather than colliding with run-control
mnemonics. Labels are associated with their editors, tables keep normal focus/selection indicators,
and status is always stated in text rather than by color alone. Font scaling covers text, row
heights, and stable table column widths from 70% through 200%.

When a breakpoint stops the machine, the header names the immutable triggering definition and its
match/stop ticks. The Breakpoint Center marks that definition while it still exists. After the
machine moves, the same information is explicitly labelled as the last historical stop rather than
the cause of a later pause.

## Reverse history and timeline capture

Reverse history and the typed timeline are separate explicit opt-ins. **Record reverse history**
enables the controller's bounded checkpoint ring when supported. **Capture trace timeline** enables
only the checked categories, with a retained capacity from 64 through 2,000 rows. The harmless
default category set is Interrupt, PPU, and Input; CPU and memory tracing remain off until selected.

The timeline reports its cursor and labels buffer overwrites, missed entries, producer drops, and
desktop-row truncation. Turning capture off, hiding/closing the window, or replacing the session
releases only trace ownership acquired by that debugger window. If a terminal port cannot confirm
disable, retries are bounded and the abandoned port cannot block timeline capture in its successor.

## Persistence and privacy

Coffee GB persists only this allow-list of presentation settings: tool-window bounds and Hold
state, the selected built-in layout and legacy pane, internal divider positions, font scale,
timeline categories, and timeline capacity. It does **not**
persist ROM or boot-ROM bytes, memory/stack/disassembly contents, graphics/audio payloads, trace
rows, breakpoint state, replay data, paths, or clipboard contents.

Hiding or holding a tool withdraws its live inspection interest and releases payloads it no longer
needs. Closing the workspace shuts down its worker and listeners. Copy is an explicit local
clipboard action; the debugger does not upload or automatically export data.

The platform-neutral safe-point, bounds, DTO, and hot-path contract is specified in
[debug-port.md](debug-port.md).
