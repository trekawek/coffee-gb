# On-screen menu UX review

Reviewed the shared desktop/Android menu renderer, every route in its visual gallery, and the
host navigation and input code. This is a heuristic/code review with rendered-screen inspection,
not a study with representative users.

The existing interface has a coherent retro identity, consistent screen geometry, large menu
rows, and a focus arrow that does not rely on color alone. The main problems were readability,
misleading interaction cues, and missing feedback.

## Improvements implemented

| Priority | Finding | Improvement |
| --- | --- | --- |
| High | Rows look interactive but ignore direct mouse clicks and screen taps. | Share hit testing with the rendered row layout; activate on release over the same target. Add clickable previous/next page arrows for file browsing. Borders, dividers, disabled entries, and unused rows stay inert. |
| High | Word spaces are narrower than the bitmap lettering, visually joining words. | Use consistent, wider runtime word spacing across all five text sizes while retaining the licensed glyph assets. |
| High | Long labels and dropdown values are truncated or crowded together. | Wrap longer labels to two lines. Align every label to the same inset and place crowded dropdowns on two lines without shrinking text. |
| High | Volume says “A Choose”, but A does nothing; other controls have the same generic hint. | Show “L/R Adjust”, “A Toggle”, “A Open”, or “A Select” for the focused control. Slider track clicks adjust one step toward the pointer. |
| High | Empty load slots show no status and advertise a load action that does nothing. | Label them “Empty” and hide the load hint until an occupied slot is selected. Keep empty slots available for saving. |
| Medium | Root Library advertises Back even when it cannot close. | Derive Back availability from the actual menu stack and host policy; hide the unavailable button glyph as well as its text. |
| Medium | Empty Recent Games offers no next step; unavailable history can select the wrong preview. | Explain how to open a ROM and select a readable entry, or a safe status row when none are readable. |
| Medium | Generic “Confirm” obscures what a destructive action will do. | Name the action in both the question and button; keep Cancel initially selected. |
| Medium | Empty menu rows look like additional controls. | Draw dividers only for occupied rows and scroll indicators. |
| Medium | A wrapped game title can push battery-save status below the four-line caption. | Put the play-time label and elapsed time on one line. |

## Basis for the review

The review uses visibility of status, familiar language, consistency, recognition, user control,
error prevention, and reduced visual clutter from
[Nielsen Norman Group's usability heuristics](https://www.nngroup.com/articles/ten-usability-heuristics/).
These principles support truthful hints, specific action labels, visible save status, and controls
that behave as their appearance suggests.

[W3C contrast guidance](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)
uses 4.5:1 for ordinary text. Existing foreground/background colors already exceed that benchmark:
the minimum contrast across each packaged widget texture is 9.00:1 for ordinary rows, 8.63:1 for
selected rows, and 8.88:1 for paper surfaces. The palette therefore stays unchanged.

[W3C target-size guidance](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html)
uses a 24 by 24 CSS-pixel minimum with specified exceptions. It is a useful reference for this
native UI, not a claim of WCAG conformance. A 72-pixel row scales to about 25 pixels when the
924-pixel menu is displayed at 320 pixels wide; text at that size still needs device-level review.

## Validation and remaining limits

The gallery can be regenerated with `CommonMenuGalleryMain`. It includes every route plus long
dropdown values, long labels and filenames, overflow lists, empty load slots, root Library, and
a wrapped pause title. Automated tests cover geometry and clipping, text metrics, focus and
navigation, empty states, and pointer activation/cancellation.

Validation passed: 101 portable UI tests, 63 targeted Swing tests (including real mouse-event
dispatch), and 24 Android JVM tests. The final paging addition was covered by the portable suite.
Android Java and test code compiled against the updated shared module; live device gestures were
not instrumented. Local visual evidence is generated under `ui-portable/target/ux-review/`.

The interface still uses a fixed raster layout and controller-style A/B hints. A responsive small
screen layout, keyboard-specific key labels, screen-reader semantics, slider dragging, and
unbounded long-caption handling need separate design work. This pass preserves the existing
save-overwrite behavior. A full accessibility audit would also need assistive-technology and
physical-device testing.

No new artwork was needed: the changes reuse the existing frame, textures, and illustrations.
