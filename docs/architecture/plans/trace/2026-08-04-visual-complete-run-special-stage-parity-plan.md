# Visual complete-run special-stage parity implementation plan

## 1. Establish failing coverage

* Extend the existing S1 visual special-stage lifecycle test with the
  TRACE_ACCURATE provider/readiness and presentation-order assertions from the
  design. Use the committed S1 complete-run `ss` segment to assert that the
  first reveal occurs only after the recorded startup hold rows and that the
  cursor has not been advanced past the reveal row.
* Add a run-branch test that installs a `BoundaryProbe`, admits a shared-clock
  destination comparator, and verifies the probe remains the playback observer,
  forwards the comparator, and still latches a later transient boundary.
* Add a complete GHZ1→SS→GHZ2 seam regression that drives the advertised S1
  special-stage row driver through every row (including dynamic-art publication
  and `verifyComplete()`), then closes SS and admits GHZ2. Assert the SS
  comparison count equals its row count and that the destination comparator is
  active before its first row. This reproduces the previous
  `expected 3728 rows but compared 0` failure rather than only checking row 0.
* Add HUD tests for a special-stage run row: input glyphs, lag count, mismatch
  count, and completion banner must follow the same coordinates/content as the
  normal trace HUD.
* Add a capture shortcut test with a logical BK2 override active and physical
  modifier keys held; confirm the chord rising edge is detected. Keep a
  separate assertion that trace/test-mode recording ownership still rejects
  user recording where it currently does.

Run the focused tests before implementation and record the red assertions.

## 2. Correct S1 startup readiness

In `Sonic1SpecialStageProvider`, override `isEntryPresentationReady()` to
return the manager's readiness predicate. Keep FAST initialization's existing
`advanceToEntryPresentation()` path. Add provider tests for both policies, then
run the S1 visual lifecycle integration test to prove the GameLoop ordering:
provider update/lag admission → presentation update/music/fade → row cursor
advance.
Update the existing FAST-entry test stub/expectation to expose readiness after
its FAST initialization, while retaining the concrete TRACE_ACCURATE hold test.

## 3. Give run special stages a shared HUD model

Introduce a small read-only HUD snapshot/model contract (errors, warnings, lag,
input/action/start masks, recent `MismatchEntry` values, and completion) shared
by the normal and special-stage renderers. `LiveTraceComparator` supplies the
normal model; the row driver supplies dynamic-art counts/recent mismatches and
the launcher supplies the current physical BK2 input row for SS. Refactor
`SpecialStageTraceHudOverlay` into the same visible contract as
`TraceHudOverlay`, keeping a compatibility constructor for existing standalone
sessions. Wire the run launcher to create this model at `SPECIAL_LOCAL`
admission. Its completion supplier is the row driver's verified completion
state. Shared-clock level admission continues to install the normal model, so
GHZ2 never inherits the SS model. Test the wiring through admission and
after-production publication, including detach/reattach and completion banner
state. Assert the visual adapter uses the same `TraceRunSpecialStageRowDriver`
admission/publication policy as headless; no visual-only row hydration or
timing is allowed.

## 4. Preserve the boundary probe while restoring destination comparison

Keep `runBoundaryProbe` installed as `PlaybackDebugManager`'s observer. Ensure
`installRunComparator` only replaces the probe delegate, resets any prepared
delegate state safely at the callback boundary, and starts the destination
session at the admitted absolute BK2 row. Add the regression proving a later
special/bonus transient request is still captured after a level destination
rebind. Verify the comparator receives destination rows and can pause/report an
input desync.

Audit the compatibility `applyRunSegmentAdvance` path as well as the current
coordinator path. It must either be proven unreachable for production launches
and guarded by a test, or preserve the probe-as-observer invariant too; no
legacy path may install a comparator directly.

## 5. Make capture modifiers physical without changing ownership policy

Add package-level raw physical modifier accessors to `InputHandler` that do not
consult `logicalOverride`. Use them only in `Engine.handleLiveCaptureShortcut`;
leave logical gameplay mapping and the user-recording trace ownership checks
unchanged. Add the focused chord regression.

## 6. Verification and integration

Run the focused visual/run/capture tests, S1 special-stage replay tests, and
the complete `*TraceReplay` sweep. Run the full Maven suite in the worktree,
compare against the recorded develop baseline, update the trace frontier log
with the GHZ1→SS→GHZ2 seam evidence, commit with required trailers, merge into
main `develop`, rerun focused/full verification, and push only `develop`.
