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
- [x] Identify slow audio-test costs; reconcile the concurrent suite-performance pass.

## Verification and delivery checklist

- [x] Focused regression checks for changed behavior.
- [x] Native visual review at default resolution and larger window scales.
- [x] Before/after rendering cost and transition measurements; report limits honestly.
- [x] Update user/configuration documentation and existing unreleased changelog entry.
- [x] Review combined change and fix regressions.
- [x] Required change-based category selection and separate structural guards; inspect skips/failures.
- [x] Reconcile upstream, integrate into main workspace develop, package and push.
- [x] Acknowledge temporary diagnostics and safely remove fully integrated worktree/branch.

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
time 11.966 → 3.649 microseconds. This isolates the checkerboard, not whole frames.
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

Candidate `9b6f6d896` is under required validation against destination base
`09f442379`. Run `20260912T172957Z-c787e149` uses the full ordinary selection plus
structural guards in separate JVMs. Final native palette and loading supplements
passed without GL errors; the gallery now contains 240 images across 40 states.
Final editor focused check: 34 tests passed, no skips.

Bounded baseline attribution: `mvn -Dmse=off -Dtest=TestObjectPlacementEncoding
test -B` on unchanged main `09f442379` completed with 3 tests, 1 failure, no
errors/skips. `commonParserPreservesDescendingFullXOrderInsideOnePlacementColumn`
still expects descending ring positions 448,384; both base and UI candidate return
384,448 following the inherited full-X ring-ordering change. Identity, assertion
type and message matched exactly. No parser/gameplay change is included here.


Completed ordinary run `20260912T172957Z-c787e149` at `9b6f6d896`:
20,404 tests, 18 failures, zero errors, 25 skips, 871.15 seconds. Three failures
were obsolete hub test drivers/wording; the two packaged-mod launch helpers now
enter Actions with Down, and the widescreen evidence checks the Browse footer.
`mvn -Dmse=off -Dtest=TestPhase3StandaloneSampleIntegration,TestSamplePlatformerIntegration,TestOrdinaryTitleScreenCommandEvidence test -B`
then passed all 10 tests, no skips (30.887 seconds).

The remaining 15 failures are inherited: the ring-ordering assertion above and
14 FBZ route/matrix cases. Twelve FBZ diagnostics matched the earlier recorded
baseline; all five viewport matrix cases were additionally checked on unchanged
`09f442379`, matching identity, exception type and full assertion message against
the candidate detail. This includes two diagnostics changed by upstream ring work.
No test assertion domain or gameplay behavior was weakened.

All 25 skips were inspected: seven absent audio-reference cases, opt-in
measurements/captures/soak cases, explicit BK2/observation prerequisites,
unavailable surfaceless EGL, and the existing CPZ spin-tube capture assumption.
Skipped cases are not passing coverage. No failure or skip records were omitted.
The category runner stopped before guards because task-list documentation changed
during the ordinary run; the completed ordinary lane is retained as evidence and
only the missing guards lane is run separately with a 20-minute timeout.

Concurrent local develop advanced to `f0fcdf818` with the independent
[suite performance pass](2026-09-12-suite-performance-pass.md). Its
[validation ledger](../validation/2026-09-12-suite-performance-pass.md) explains
and measures the audio costs: repeated canonical preparation, byte-at-a-time
capture reads, and the deliberately large 434,417-frame/32 MiB comparison test.
It preserves coverage while reducing focused comparator time 132.859 → 78.641 s.
This UI run predates those optimizations; its comparator class took 147 seconds.
The integration must retain the performance changes and exercise the shared
network path plus UI flows. No new audio optimization is attributed to this UI patch.


The missing guard lane completed with
`LUA_BIN=/usr/bin/lua5.4 timeout --signal=TERM --kill-after=15s 20m mvn -Dmse=off -Pguards test -B`:
82 classes, 657 tests, zero failures/errors/skips, 3m54s. This was completion of
the unstarted lane, not a second ordinary selection. All production UI sources
remained at `9b6f6d896`; only the three test expectations and validation prose
changed after that run. Upstream performance delivery is now `2dd0de646`.


Integration: `5b1a7d076` merged the UI branch into develop on top of performance
commit `2dd0de646`. Git reconciled the shared changelog automatically; both entries
were inspected. All 60 UI source/test files exactly match the verified task branch;
upstream production changes are in separate host-clock/capture-store files.
The focused integrated command selected `TestMenuInput,TestMenuRepeat,TestMasterTitle*,TestMenuTextEditor,TestMenuPathBrowser,TestMenuLoadTask,TestEngineSettings*,TestModManagerScreen,TestPendingModStateEditor,TestServerBrowserScreen,TestRaceLobbyScreen,TestDirectConnectEndToEnd,TestEditorCommandPalette,TestEditorRenderingSmoke,TestMenuPixelFont,TestPhase3StandaloneSampleIntegration,TestSamplePlatformerIntegration,TestOrdinaryTitleScreenCommandEvidence`
with `mvn -Dmse=off -Dtest=... test -B`: 245 tests passed, zero skips, 1m02s.
The complete ordinary/guard evidence above and the independently completed
performance validation are retained; the merged overlap is checked narrowly,
without repeating broad suites on already tested unchanged source.


Integrated packaging: `mvn -Dmse=off -DskipTests package -B` completed successfully
in 35.520 seconds at `5b1a7d076`. Test execution was deliberately skipped in this
packaging invocation because the relevant validation was already complete.
Native screenshots and benchmark harnesses are outside the repository at
`<agent-scratch>/ui-review/`; `visuals/index.html` contains
240 captures. The worktree contains no uncommitted or unmerged tracked work.
Its ignored items are generated Maven/probe/image-cache outputs, checkout links,
and a config example identical to the preserved main copy.


Delivery: merge `5b1a7d076` was pushed to origin/develop. The category diagnostics
were acknowledged and deleted. After confirming integration and push, the clean
`ui-review` worktree and fully merged `feature/ai-ui-review` branch were removed,
and stale worktree metadata pruned. Main-workspace disassembly changes and the
unrelated review notes remain untouched. The task is complete with the 15
explicitly inherited ordinary failures above; it is not an all-green suite claim.
