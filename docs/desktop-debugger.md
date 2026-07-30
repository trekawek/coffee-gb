# Desktop debugger

Open **Tools > Debugger** to show Coffee GB's modeless debugger window. The window can remain open
while the game runs and never calls the emulator core directly: every command goes through the
current session's `DebugPort` and is applied at an emulation safe point. A linked/rollback session
may publish an inspection-only or unsupported port; the window reports the negotiated capabilities
instead of offering unsafe one-machine controls.

## Snapshot identity and panes

The header identifies every view as `Session …, snapshot …, tick …`. Registers, machine state,
stack, disassembly, requested memory, peripheral data, and a trace page returned by one refresh all
come from that same coherent safe point. A run-control or CPU-only refresh immediately releases
older Graphics and Audio rows and labels those panes as not captured for the new identity; they do
not remain visible under a newer header while their selected-pane refresh is prepared.

Only the selected Graphics or Audio section is requested. The detached payload is decoded on one
bounded worker and only payload-free table rows reach Swing's event thread. Changing session,
hiding, or closing the debugger cancels that work and releases its snapshot and raw bytes.

| Pane | Contents |
| --- | --- |
| **CPU** | Registers and flags, execution/interrupt/timer/PPU/APU scalars, mapper banks, stack, and side-effect-free disassembly. |
| **Memory** | One validated ROM, work-RAM, or high-RAM address/range, read only while paused. |
| **Breakpoints** | Searchable, sortable Breakpoint Center for PC, memory, opcode, interrupt, PPU, serial, master-tick, and frame conditions. Definitions can be added, edited, duplicated, enabled/disabled, or removed without blocking the UI. |
| **Graphics** | Hardware mode, VRAM tile banks, background/window maps, all 40 OAM objects, DMG registers, and CGB RGB555 palettes. |
| **Audio** | Mixer/frame-sequencer state, four APU channels, raw/decoded registers, and all 32 wave samples in text plus a supplemental graph. |
| **Timeline** | Sequence-ordered typed events, including serial and input activity when those categories are selected. |

Address fields accept hexadecimal forms such as `$C000`, `0xC000`, or `C000`; ranges use a hyphen.
Invalid, side-effectful, oversized, or unsupported requests are reported in the status line and do
not mutate the machine. Table and color-preview information is also available as text.

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

Coffee GB persists only this allow-list of presentation settings: window bounds, three divider
positions, selected pane, font scale, timeline categories, and timeline capacity. It does **not**
persist ROM or boot-ROM bytes, memory/stack/disassembly contents, graphics/audio payloads, trace
rows, breakpoint state, replay data, paths, or clipboard contents.

Hiding the retained window clears snapshots, tables, cursors, breakpoints, and reverse-history
status before it can be reopened. Closing additionally shuts down its worker and listeners. Copy is
an explicit local clipboard action; the debugger does not upload or automatically export data.

The platform-neutral safe-point, bounds, DTO, and hot-path contract is specified in
[debug-port.md](debug-port.md).
