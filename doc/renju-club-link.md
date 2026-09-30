# Renju Club: Gomoku Narabe link investigation

Investigated 2026-09-30 against Coffee GB `2e6b1c18`, using the authorized Japanese
SGB-enhanced release in DMG mode. **Link-cable play is confirmed.** The earlier
hot-seat-only conclusion was wrong.

The startup timing hint in [report #692](https://github.com/trekawek/coffee-gb/issues/692)
explains the missing experiment. The previous investigation started two identical
machines together, including its SameBoy comparison. That setup did not establish
that the game lacked a link protocol. [PR #690](https://github.com/trekawek/coffee-gb/pull/690)
changed general SGB netplay setup, not this game's startup handshake.

## What the game does

The game negotiates serial roles once during startup, before the title screen:

1. Try to become clock master, sending `F1` and expecting `F0`.
2. On that response, wait six VBlanks, send `F2`, and expect `F3`.
3. After five unsuccessful attempts, separated by three-VBlank waits, offer `F0`
   using external clock and remain a passive listener.
4. A listener answers a later console's `F1` with `F3`, then accepts its `F2` and
   becomes the slave.

The negotiation entry is at `$1448`; the passive-listener setup is at `$143C`.
The game's role variable at `$FFDA` is `0` for listening, `1` for master, `2` for
slave, and `4` while attempting master selection. These are diagnostic game
states, not emulator configuration values.

Two identical machines running in strict lockstep can exhaust their attempts
at the same time and then both wait for an external clock. Attaching the cable
after both machines have already reached that listening state also fails:
**waiting longer after attachment does not restart the handshake.** A later
startup probe, with the cable attached, gives the listening machine a partner.

The hint concerns time passing inside the emulated machines. A wall-clock pause
while both machines are frozen does not change their startup relationship.
Simply delaying cable attachment until both copies are idle is insufficient.

## Experiments

Private headless harnesses read the ROM in place, disabled battery persistence,
and captured both screens and serial-register writes. One used paired core
`Gameboy.tick()` calls; another used the actual controller `LinkedFrameStepper`.
Scalar frame budgets are 70,224 master ticks. The controller experiment used
`ClockSpec.LEGACY`: 69,905 ticks per controller frame, plus any unilateral
role-election advances. No emulator source was changed.

| Execution and setup | Observed result |
| --- | --- |
| Scalar core, cable attached before identical SKIP starts, run 300 frame budgets | Both remain listeners: roles `0/0`, six transfer starts each, no working link. |
| Scalar core, both run disconnected for 500 budgets, attach later | Both remain listeners; no new transfer starts. |
| Scalar core, first machine runs alone for 120 budgets, attach cable, start second, run both for 300 budgets | Roles `2/1`, sustained serial traffic, shared menus and working play. |
| Same 120-budget startup gap with authentic NORMAL boot, then run both for 500 budgets | Roles `2/1`, sustained serial traffic. The result is not specific to skipped boot. |
| Controller stepper, fresh identical SKIP starts with cable attached | Roles `1/2`, sustained serial traffic through 600 controller frames. Existing role-election handling breaks the symmetry. |
| Controller stepper, both run disconnected for 500 budgets before attachment | Still roles `0/0` after 600 controller frames; each remains at six transfer starts. |
| Controller stepper, reset only the second machine after that failed attachment | Roles `2/1`, sustained serial traffic through 600 controller frames. |

The 120-budget gap is about two emulated seconds; it is a verified reproduction
value, not a measured minimum.

Gameplay was verified in the staggered scalar session. The later-started master
controlled both menus. After selecting `1P VS 2P` and confirming the opening
pattern, a white stone placed using the second console appeared on both boards
(move count `004`). A black stone placed using the first console then appeared
on both boards (move count `005`). This verifies input and move propagation in
both directions, beyond merely observing a handshake or matching title screens.

## Reproduction and workaround

For a deterministic headless reproduction:

1. Use SKIP boot and DMG mode for both machines, with battery writes disabled.
   Start the first without a cable and advance it by `120 * 70224` ticks while the second machine remains at its initial state.
2. Attach paired `Peer2PeerSerialEndpoint`s between ticks using
   `Gameboy.setSerialEndpoint`, then advance both machines in scalar lockstep.
3. After 300 frame budgets, use the second console to press START, A, DOWN, A,
   DOWN, A. This selects the normal game, `1P VS 2P`, and starts opening selection.
4. Confirm the opening pattern with A on the second console. Its controls operate
   the shared menus; directions on the first console do not control those menus.
5. Play each turn on its owning console. With the default opening, the second
   console places the next white stone; the first console places the following
   black stone. Choose an empty intersection before pressing A.

For an initial failed netplay connection with both copies already at the title,
keep the connection and **reset only one console**. The unchanged console is
still listening and the reset console initiates a new negotiation. This recovery
was tested through controller sessions; the desktop UI was not exercised.
`LinkedController` resets the selected player rather than both players.
The workaround is scoped to initial connection failure: resetting one side of
an already established game did not re-establish that session in the probe.

## Coffee GB fix

`createNetplayLoadEvent` normally carries each standalone machine's state into
netplay. The Renju cartridge profile now marks this game as
`isLinkRequiredAtBoot`, so two-player netplay creates fresh machines and starts
their one-time role elections with the cable attached. The existing
`LinkedFrameStepper` resolves competing fresh elections.

The controller regression test verifies that Renju's normal link session discards
the pre-link state and autosave resume, while four-player adapter sessions and
other cartridges keep their previous behavior. The existing fresh-connected
controller experiment verifies role election, but an end-to-end TCP session with
two Renju game instances was not part of this change.
