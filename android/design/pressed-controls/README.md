# Pressed control artwork

Generated with the built-in ImageGen tool. The selected transparent source sprites are in `source/`.

The production atlases are `../../app/src/main/res/drawable-nodpi/coffee_gb_pressed_portrait.png`
and `../../app/src/main/res/drawable-nodpi/coffee_gb_pressed_landscape.png`. Each atlas is 576×672 RGBA.
Their native artwork bounds and source slots are defined in `PressedControlLayout`.

`portrait-states.png` and `landscape-states.png` show all 13 states: A, B, A+B, Select, Start,
Up, Up+Right, Right, Down+Right, Down, Down+Left, Left, and Up+Left. Individual previews live
in `states/portrait/` and `states/landscape/`. `*-preview.png` shows Up+Left with A+B at phone size.
These are compositions of the actual production sprites over the neutral DMG skin; the CGB and
SGB skins share the same control artwork and coordinates.

The D-pad master supplies four independently masked arms. Disjoint directional sectors and a
soft transition around the neutral center keep unrelated arms unchanged. Diagonals compose
the two relevant arms. Select and Start share a generated capsule treatment. A+B composes both
action sprites with the pressed center cue. The cue remains above either individual button.

The preparation tool crops transparent margins, scales the generated sprites, clips them to the
original control outlines, and packs the atlases. It does not redraw the generated symbols or materials.
Run it from the repository root after compiling `TouchControlsLayout`, `SkinTransform`,
`TouchPressState`, `PressedControlLayout`, and `tools/PreparePressedControls.java` into a scratch
classpath with `core/target/classes`. Main class: `eu.rekawek.coffeegb.android.PreparePressedControls`;
its sole argument is the absolute repository root.

Runtime feedback unions held touch pointers, including diagonals and concurrent D-pad/action
chords. Sliding, pointer release, cancellation, focus loss, activity pause, surface loss and detach
clear the relevant visual state. Menu skin controls also light up; touches on menu content do not.
A state transition rebuilds the existing cached skin layer; held states add no per-frame sprite draws.
Only the current orientation's pressed atlas is decoded. Touch hit geometry is unchanged.

## Validation

The debug app and instrumentation APK build successfully. All 25 selected JVM tests pass
(`TouchPressStateTest`, `TouchControlsLayoutTest`, `SkinTransformTest`,
`CoffeeGbSurfaceViewTest`, and `AndroidInputRouterTest`). All 6 Android API 26 emulator tests
pass (`PressedControlsAndroidTest` and `RasterSkinAndroidTest`), covering rendering on all six
skins, the eight D-pad direction states, touch slides/cancellation/focus loss, and aperture geometry.

## Final ImageGen prompts

### a

```text
Use case: precise-object-edit
Asset type: transparent production sprite for a PRESSED retro handheld controller button.
Input image 1 is the edit target: keep this exact button silhouette, proportions, orientation, and symbol placement.
Change its state to visibly pressed: deep muted burgundy satin plastic (#713F43) on the face, darker inset inner bevel, restrained warm upper-left edge highlight, reduced raised shadow. The state must look distinct from the original black neutral button at small phone size. Keep the outside rim charcoal-black and any symbol warm ivory (#E9DDC9).
Strict orthographic front view. Preserve shape and perspective. Remove all cream shell/background outside the control and return actual transparent alpha with the complete button isolated, centered with a narrow transparent margin. No glow, no neon, no hands, no labels outside the button, no surrounding controller, no watermark.
Subject: the single circular A button. Exact centered text: "A". Keep the letter the same size and style as the reference, but warm ivory in the pressed state.
```

### utility

```text
Use case: precise-object-edit
Asset type: transparent production sprite for a PRESSED retro handheld controller button.
Input image 1 is the edit target: keep this exact button silhouette, proportions, orientation, and symbol placement.
Change its state to visibly pressed: deep muted burgundy satin plastic (#713F43) on the face, darker inset inner bevel, restrained warm upper-left edge highlight, reduced raised shadow. The state must look distinct from the original black neutral button at small phone size. Keep the outside rim charcoal-black and any symbol warm ivory (#E9DDC9).
Strict orthographic front view. Preserve shape and perspective. Remove all cream shell/background outside the control and return actual transparent alpha with the complete button isolated, centered with a narrow transparent margin. No glow, no neon, no hands, no labels outside the button, no surrounding controller, no watermark.
Subject: the single narrow horizontal capsule button, used for Select and Start. Preserve its long low capsule proportions. There is NO text or symbol on this button. Only its face becomes burgundy.
```

### dpad

```text
Use case: precise-object-edit. Asset type: production master sprite for pressed D-pad arms. Image 1 is the edit target, image 2 is the material/style reference. Keep the EXACT cross silhouette, limb widths, circular center, four triangular arrow positions, proportions and straight-on orientation of image 1. Make all four arm faces visibly pressed using the same recessed deep muted burgundy satin plastic as image 2, with charcoal-black outer rims, reduced raised bevel and subtle upper-left highlights. Each of the FOUR triangular direction arrows is solid warm ivory: up, down, left, right. Keep the circular CENTER HUB charcoal black with no marking. This master will be sliced into four independently highlighted arms for single directions and diagonals, so all arms must use exactly the same consistent treatment and unchanged geometry. Remove the cream shell entirely outside the cross and return a tightly framed isolated cross with real transparent alpha. No background, glow, fingers, extra controls, added text, labels or watermark. Preserve all four equal arm lengths and square cross proportions.
```

### b

```text
Edit this transparent pressed A-button sprite into the matching B-button sprite. Change ONLY the letter A to an uppercase letter B in the same warm ivory color, size, font weight and position. B has ivory vertical and curved strokes with two burgundy holes. Preserve all other pixels and the exact transparent alpha background, circular burgundy button, charcoal rim and lighting. The output must have real transparency outside the button. Do not add any checkerboard or background.
```

### bridge

```text
Use case: ui-mockup. Generate one isolated production sprite on a genuinely TRANSPARENT ALPHA background: a small round pressed "+" control for a cream retro handheld. Strict straight-on orthographic view. A softly beveled warm cream outer circular ring (#D9CDBF) surrounds a shallow recessed deep burgundy satin plastic circular face (#713F43). In the center is a bold ivory plus sign (#E9DDC9), upright with equal arms, roughly half the overall diameter. Soft restrained upper-left illumination, molded plastic texture. Complete circular silhouette centered, occupies 92 percent of square canvas, narrow transparent margin. The plus means A+B pressed together. No letters, no words, no hands, no second button, no metal, no neon, no glow, no cast shadow outside silhouette, no background whatsoever. Most critical: actual transparency outside the circular sprite; do NOT depict a checkerboard, gray pattern, white paper, or black rectangle.
```
