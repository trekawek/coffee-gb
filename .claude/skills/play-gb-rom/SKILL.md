---
name: play-gb-rom
description: Run an authorized local Game Boy or Game Boy Color ROM in one persistent headless Coffee GB session, inspect captured frames, send controller input interactively, and retain a frame-accurate action trace for replay or test authoring. Use when asked to play, navigate, explore, reproduce, or automate gameplay without restarting the emulator between inputs.
---

# Play GB ROM

Keep one emulator process alive, inspect its latest PNG, decide the next input, and append every
action to a private trace. Use this loop for long playthroughs as well as short reproduction work.

## Start one private session

1. Work from a clean or task-specific Coffee GB checkout.
2. Resolve exactly one user-authorized ROM. Honor local `AGENTS.md` instructions. Never guess among
   ambiguous revisions, and never print, copy, hash, upload, or commit the ROM or save data.
3. Start the bundled driver in a PTY so stdin stays open:

   ```text
   .claude/skills/play-gb-rom/scripts/play-gb-rom.sh <authorized-rom>
   ```

   Use a tool invocation with `tty: true` and a short initial yield. The wrapper builds Coffee GB,
   creates a mode-700 directory under `/tmp`, compiles the driver there, and prints the private
   session directory plus sanitized build progress. A cold build may take about a minute. It reads
   the ROM in place and does not copy or symlink it.
4. Keep the returned process/session ID. Do not relaunch the driver for each action.

The current `Agent` backend intentionally disables battery writes and attaches a null serial
endpoint. A session therefore starts from the ROM's normal initial state, retains progress only
while the process lives, and cannot exercise link peripherals. Report this limitation when the
requested playthrough depends on saves, RTC persistence, multiplayer, printer, or Mobile Adapter
traffic.

## Drive and inspect

Send one newline-terminated command at a time:

```text
BUTTON A
BUTTON RIGHT 12 60
STEP 120
CAPTURE map
STATUS
QUIT
```

- `BUTTON <RIGHT|LEFT|UP|DOWN|A|B|SELECT|START> [hold_frames] [dwell_frames]` presses, advances the
  held frames, always releases, advances the dwell frames, and captures the resulting frame.
  Defaults are 3 held frames and 30 dwell frames.
- `STEP <frames>` advances without input and captures the resulting frame.
- `CAPTURE [label]` records the current frame without advancing. Labels are optional safe tokens.
- `STATUS` reports emulated tick/frame, CPU/PPU state, and the latest frame token without advancing.
- `QUIT` releases input, closes the owner thread, and leaves the private trace and frames available.

Counts are bounded per command. For long waits, issue several `STEP` commands so the user receives
regular progress updates and tool calls do not block for more than 60 seconds.

After each action, open the reported PNG from `<session>/frames/` with the local image-viewing tool.
Use the visible screen—not guessed menu timing—to choose the next input. Keep commentary concise for
long runs: report milestones, ambiguity, and blockers rather than narrating every button.
The initial one-frame capture may legitimately be black during startup; issue `STEP` and inspect the
next capture before classifying that as a failure. Use about a one-second stdin-tool yield for normal
commands because the resident process remains alive after printing its result; poll again only when
no action marker has arrived.

## Preserve reproducibility

`actions.tsv` records command order, starting and ending emulated positions, hold/dwell/step frame
counts, and PNG basenames. It deliberately contains no ROM identity or filesystem path. Treat the
trace as the source for a later deterministic input script or integration test; copy only generic
button/timing facts into repository tests or documentation.

When a destination is ambiguous, capture and inspect instead of restarting. If an action produces
an unexpected screen, continue from the resident session when safe and record the correction. Do
not claim a complete playthrough from transport or memory evidence alone; confirm the visible end
state.

## Finish safely

Send `QUIT`, confirm `cleanup_buttons=true` and `session_closed=true`, then remove only the exact
private session directory when its frames and trace are no longer needed. Prefer `QUIT` over a signal
so the completion markers are observable; the shutdown hook still releases input and closes the
Agent if the process is interrupted. Never commit or publish commercial screenshots, the action
trace if it identifies private gameplay, generated class files, ROMs, saves, or local paths.
