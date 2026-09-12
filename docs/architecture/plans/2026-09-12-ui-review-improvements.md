# UI review improvement delivery

Approved scope: all findings from the UI review at `09f442379`. Preserve the
320×224 logical layout, checkerboard styling, original ROM logos, and
white/default, amber/non-default, red/experimental status meanings.

## Implementation checklist

- [x] Controller-accessible native-mod notices with all text paginated.
- [x] Crisp startup notice fonts and device-appropriate prompts.
- [x] Room refresh results applied on the UI thread; selection follows room identity.
- [x] Shared hold-repeat for navigation, caret movement, and deletion; no repeating confirmation.
- [x] Consistent Mods/Settings draft, Apply, and Back/discard behavior.
- [x] Clear immediate/restart setting effects.
- [x] Shared manually scrollable details for settings, chat, and long validation errors.
- [x] Browse-all-games list alongside the carousel, including availability.
- [x] Shared input field modes: numeric keypad, address punctuation, path browsing.
- [x] Readable non-ASCII text handling without corrupting stored values.
- [x] Controller-family prompts or user-selectable prompt style.
- [x] Controller-accessible experimental editor command palette.
- [x] Correct obsolete trace-picker navigation wording.
- [x] Batched checkerboard rendering and no redundant covered page background.
- [x] Asynchronous, cancellable trace/recording catalog loading.
- [x] Selective ROM-preview invalidation.
- [x] Cached settings presentation data keyed by actual changes.

## Verification and delivery checklist

- [x] Focused regression checks for changed behavior.
- [ ] Native visual review at default resolution and larger window scales.
- [x] Before/after rendering cost and transition measurements; report limits honestly.
- [x] Update user/configuration documentation and existing unreleased changelog entry.
- [ ] Review combined change and fix regressions.
- [ ] Required change-based category selection and separate structural guards; inspect skips/failures.
- [ ] Reconcile upstream, integrate into main workspace develop, package and push.
- [ ] Acknowledge temporary diagnostics and safely remove fully integrated worktree/branch.

## Design decisions and evidence

The approved review supplies the design direction. Independent changes use
separate file ownership. One coordinated validation run owns the worktree's
Maven output. No Mod API signature changes are intended unless required and
then verified against the repository's compatibility policy.

Implementation boxes indicate code is present; verification/delivery boxes below
remain open until their evidence is complete. Unsupported characters are represented
by Unicode identities in the ASCII pixel font; this is not full Unicode glyph art.

Measured checkerboard comparison (native Mesa/RX 9070 XT, 320 logical pixels at
4×, five alternating 1,000-draw rounds): 110 submissions become one; median GPU
time 11.979 → 3.676 microseconds. This isolates the checkerboard, not whole frames.
Settings presentation (recording font, no GL, warmed Java 21): allocation per
unchanged render 51,424 → 480 bytes; CPU median 19,219 → 105 ns. These synthetic
measurements establish the specific removed work, not an engine-wide speedup.

Trace catalog transition benchmark, actual 73-entry repository catalog: synchronous
scan occupied the caller for 647 ms on first invocation and 302–319 ms warmed.
Background task submission returned in 3.52 ms first invocation and 0.032–0.047 ms
warmed; catalog readiness remained 330–390 ms. Exact catalog results matched.
This removes the UI stall; it does not make the underlying scan faster.

Focused evidence so far: combined 347 tests passed with no skips; subsequent
58-test check caught only a stale saved-status assertion after the Details hint
prefix changed. The assertion was corrected without changing persistence checks.
Preflight uses Java 21, explicit `LUA_BIN=/usr/bin/lua5.4`, and PowerShell; default
`lua` is a newer incompatible version. Required plan selects all 2,511 ordinary
classes and structural guards, with 40-minute total/10-minute silence limits.

Native visual review: 222 captures across 37 states at 320/400 logical widths and
1×/2×/4× window scale, plus supplementary loading/editor palette views. The native
core-profile palette capture exposed GL_INVALID_ENUM from a new GL_QUADS backdrop;
changed it to GL_TRIANGLE_FAN and added a primitive/order regression before delivery.
Root inspected game-browser, numeric keypad, PlayStation settings footer and
notice pagination captures; original ROM logos and checkerboard styling remain.
