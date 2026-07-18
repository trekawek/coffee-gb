---
name: fix-compatibility-issue
description: Reproduce, investigate, fix, test, and submit a Coffee GB game or ROM compatibility issue as an isolated GitHub pull request, with issue follow-up and visual evidence when possible. Use when asked to address a specific Coffee GB GitHub compatibility issue.
---

# Fix Compatibility Issue

Handle one GitHub issue per branch and pull request. Preserve evidence from the failing behavior through the verified fix.

## 1. Read and classify the issue

1. Require an issue number or URL, then read its title, body, comments, labels, linked PRs, and every supplied attachment with `gh issue view` and the GitHub API as needed.
2. Record the affected ROM title and region/revision, DMG/CGB/SGB mode, expected behavior, observed behavior, reproduction steps, save state or save data, and any timing or input requirements.
3. Check whether the issue includes a legally usable ROM or a precise ROM identity. Never publish or attach copyrighted ROM data.
4. If no ROM is included, locate it in `./roms/gb` or `./roms/gbc`. Match title, platform, region, revision, and checksum when the issue provides them. Do not substitute a similarly named ROM silently.
5. Read the relevant technical sections of `CLAUDE.md` and `doc/derived/` before changing timing-sensitive CPU, PPU/STAT, APU, timer, or interrupt behavior.

If the issue lacks enough information and no matching ROM exists in the local database, comment with the missing details and stop instead of guessing.

## 2. Isolate the work

1. Ensure the working tree is clean and fetch the remote default branch.
2. Create a branch from the current remote default branch named `codex/issue-<number>-<short-slug>`.
3. Keep unrelated user changes and unrelated issue fixes out of the branch.

## 3. Reproduce before editing

Follow the issue steps on the reported hardware mode and ROM revision. Prefer the controller Agent API and the integration support mains described in `CLAUDE.md` for deterministic runs.

- Capture exact emulator configuration, commit, ROM checksum, inputs, frame/tick bounds, and observed output.
- Add the narrowest useful automated regression test when the behavior can be asserted deterministically.
- For a visual defect, capture a baseline screenshot at a stable frame. Do not use a different ROM revision or a nondeterministic frame as evidence.
- Compare traces, memory, frames, or audio with hardware-backed test suites and independent references as appropriate. Another emulator is diagnostic evidence, not the compatibility specification.

If automation cannot reproduce the issue after reasonable attempts, do not change code or create a speculative PR. Add an issue comment beginning with `**AI-generated comment:**` that summarizes the ROM identity/checksum, environment, steps attempted, observed result, and states: `The problem could not be reproduced by automation and will be triaged manually.` Leave the issue open and stop.

## 4. Investigate and fix the cause

Trace the smallest subsystem that explains the divergence and establish why the current behavior is wrong before editing it. Prefer a general hardware-compatible correction over a game-specific workaround.

- Keep the patch scoped to the issue.
- Preserve save-state mementos when adding state.
- Add or update a regression test that fails before and passes after the fix whenever practical.
- Remove temporary probes, debug output, generated files, ROMs, save data, and scratch artifacts before committing.

Run the focused regression test, affected module tests, and relevant integration profiles. For timing, interrupt, PPU, APU, or mapper changes, run the broader compatibility suites identified in `CLAUDE.md` in proportion to the risk. Record exact commands and results.

Re-run the original reproduction steps. For a visible fix, capture one stable after-fix screenshot showing the corrected state; prefer the same frame/configuration as the baseline.

## 5. Create the pull request

Review the diff and commit only the scoped source and test changes. Push the issue branch and create a non-draft PR against the default branch.

The PR description must explain:

- the compatibility symptom and affected ROM/mode;
- the root cause and why the old behavior was incorrect;
- what the patch changes and why it is the appropriate fix;
- regression coverage and all test commands/results;
- `Fixes #<issue>` when the PR genuinely resolves the issue;
- the after-fix screenshot when one exists and can be uploaded.

Do not claim hardware accuracy beyond the evidence collected.

## 6. Update the original issue

After the PR URL exists, add a short issue comment beginning with `**AI-generated comment:**`. Link the PR and summarize the fix in one or two sentences. Attach or embed the after-fix screenshot when the available GitHub tooling supports image upload. If it cannot be uploaded, do not commit the image merely to manufacture a URL; mention the screenshot in the PR and keep the issue note concise.

Finally report the branch, commit, PR URL, tests, reproduction result, and screenshot status. Do not merge the PR as part of this skill.
