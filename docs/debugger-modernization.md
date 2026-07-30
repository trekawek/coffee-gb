# Debugger modernization

Coffee GB is borrowing Mesen's debugger workflow, not its implementation or visual styling. The
reference baseline is the official MesenCE source at
[`b2b9497`](https://github.com/nesdev-org/MesenCE/tree/b2b9497c1d4a1859deffeda4f0e3192dad46a44c).
The useful pattern is composability: a location discovered in a stop reason, trace row, watch, or
hardware view should lead to the same navigation and breakpoint actions.

## Reference audit

Mesen's debugger combines a navigable disassembly workspace, typed breakpoints, watches, decoded
hardware state, trace/event tools, symbols, and a per-ROM workspace. Its default dock layout keeps
code, status, watches, breakpoints, and call stack visible together; the saved layout can be
restored on the next run. See MesenCE's
[`DebuggerDockFactory`](https://github.com/nesdev-org/MesenCE/blob/b2b9497c1d4a1859deffeda4f0e3192dad46a44c/UI/Debugger/DebuggerDockFactory.cs)
and
[`DebugWorkspaceManager`](https://github.com/nesdev-org/MesenCE/blob/b2b9497c1d4a1859deffeda4f0e3192dad46a44c/UI/Debugger/Utilities/DebugWorkspaceManager.cs).

The highest-value workflows are:

1. **Explain every stop.** Show the triggering breakpoint and condition next to the paused state.
2. **Make stop conditions discoverable.** Treat execution, memory, opcode, interrupt, video,
   serial, and time/frame conditions as one searchable breakpoint workflow.
3. **Make locations portable.** Disassembly, memory, traces, watches, labels, and hardware state
   should share navigation and actions.
4. **Keep context visible.** Code, registers, watches, breakpoints, and call stack should not require
   repeated modal-window or tab switching.
5. **Highlight change.** Watches, registers, memory, and event streams should make new values easy
   to distinguish without relying only on color.

Mesen examples include its
[`DisassemblyView`](https://github.com/nesdev-org/MesenCE/blob/b2b9497c1d4a1859deffeda4f0e3192dad46a44c/UI/Debugger/Views/DisassemblyView.axaml),
[`WatchListView`](https://github.com/nesdev-org/MesenCE/blob/b2b9497c1d4a1859deffeda4f0e3192dad46a44c/UI/Debugger/Views/WatchListView.axaml),
and Game Boy
[`GbRegisterViewer`](https://github.com/nesdev-org/MesenCE/blob/b2b9497c1d4a1859deffeda4f0e3192dad46a44c/UI/Debugger/RegisterViewer/GbRegisterViewer.cs).

## Coffee GB capability map

Coffee GB already has a strong asynchronous safe-point boundary. `DebugPort` provides coherent
inspection, pause/resume, forward and reverse stepping, bounded tracing, seven typed breakpoint
kinds, and last-hit metadata. Swing originally exposed only PC and memory breakpoints and rendered
an automatic stop like an ordinary pause.

| Workflow | Backend | Swing before modernization | Direction |
| --- | --- | --- | --- |
| Typed stop conditions | PC, memory, opcode, interrupt, PPU, serial, counter | PC and memory only | Breakpoint Center |
| Exact stop reason | `lastBreakpointHit()` | Not queried | Correlated stop banner and hit row |
| Forward/reverse control | Instruction and frame | Available | Shared action registry and configurable shortcuts |
| Disassembly | Safe detached bytes at PC | One formatted instruction | Bank-aware location model, listing, navigation history |
| Watches | Bounded coherent reads are available | None | Typed watch model with changed-value presentation |
| Event inspection | Bounded typed trace | Timeline table | Filters, detail view, address navigation, raster correlation |
| Hardware state | Decoded CPU/PPU/APU/mapper DTOs | Mostly text/tables | Field actions and change markers |
| Workspace | Harmless layout preferences | Fixed tabs and splitters | Versioned pane IDs, layout reset, optional per-ROM data |
| Mutation | No mutation command by design | Read-only | Explicit atomic safe-point protocol before any editor |

## Delivery sequence

### 1. Breakpoint Center

- Card-based editor for every breakpoint kind already negotiated by the session.
- Searchable, sortable table with create, edit, duplicate, enable/disable, remove, and current-PC
  toggle actions.
- A stop banner and table marker derived from `lastBreakpointHit()` and correlated with the current
  snapshot, so a historical hit is never described as the current pause cause.
- Last-hit metadata retains the immutable triggering definition and an explicit active/historical
  pause state, so edits, ID reuse, recapture, rewind, or state restore cannot rewrite the story of
  an earlier stop.
- Session-local state only; no breakpoint condition or hit data is persisted.

### 2. Shared commands and locations

Introduce stable pane and command identifiers, one capability-aware action registry, configurable
shortcuts, a command palette, and bounded back/forward navigation. Define a bank-aware
`DebugLocation` before expanding disassembly: a CPU address alone is not enough to identify code in
the switchable Game Boy ROM window.

### 3. Watches and coherent inspection planning

Add typed address/register watches, explicit display formats, old/new values, and accessible change
text. Coalesce adjacent reads and keep the aggregate request inside `DebugInspectionRequest` bounds.
Expressions should be shared later with conditional breakpoints and trace filters rather than
implemented three times.

### 4. Disassembly and source workflow

Add a structured instruction DTO, a bank-correct listing, current-PC and breakpoint gutters,
go-to/back/forward, run-to-cursor, and step-over/out semantics. Then layer RGBDS/SDCC symbol and
source mapping onto the same location model.

### 5. Event and hardware tools

Turn the typed timeline into a filterable event inspector with details and cross-navigation. Add a
raster position view for PPU events and actions from decoded LCD/APU/timer/DMA/interrupt fields to
the breakpoint editor.

### 6. Explicit mutation protocol

Register or memory editing must not bypass `DebugPort`. It requires an atomic request containing
the expected session and snapshot identity, capability-gated safe RAM/register targets, and defined
history/trace invalidation. Swing editing should be added only after controller and queued-port
tests prove those semantics.

## Non-negotiable boundaries

- No Swing component accesses the live emulator directly or blocks the EDT on a debug command.
- UI completions remain correlated by window epoch, client identity, session generation, and
  request ID.
- ROM, boot ROM, memory captures, hit data, paths, and trace payloads are not silently persisted.
- Unsupported linked/rollback topologies remain inspection-only and never expose unsafe controls.
- Every state or change marker has a textual equivalent; color is supplemental.
