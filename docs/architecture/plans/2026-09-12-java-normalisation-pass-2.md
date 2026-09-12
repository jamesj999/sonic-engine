# Java normalisation: second review

Status: implementation in progress on `develop`, one commit per candidate.

Reviewed `develop` at `5d9ef4af40d052050d37df2424e696608cff2647` on
12 September 2026. Main workspace only; no worktrees or branch changes.

## Scope

This pass searched 3,106 production Java files for repeated noncomment blocks,
then inspected candidate implementations, callers, existing shared owners and
test names. It also reviewed the first pass's completed implementation record
and validation handoff. This is static evidence, not a whole-program equivalence
proof. No engine tests were run for this plan.

The proposals below are additional boundaries, not a relisting of the first
pass's shields, CPZ presentation, preview cache, profile adapters or large
controller assessment. Remaining repeated facade methods in those areas are
not automatically defects: some deliberately preserve API or snapshot shape.

## Proposed changes

### 1. Remove unnecessary solid-contact listener obligations

**Evidence:** the source scan found **84** `onSolidContact` implementations
whose bodies are empty or contain only an unused cast to `AbstractPlayableSprite`.
Examples: `MCZDrawbridgeObjectInstance.java:424`, `CogObjectInstance.java:456`,
`Sonic1FloatingBlockObjectInstance.java:327` and
`Sonic1GirderBlockObjectInstance.java:212`.
`SolidObjectListener` requires the callback but already describes it as a
compatibility adapter. Solidity itself is supplied by `SolidObjectProvider`.

**Proposal:** remove listener participation from objects that need neither contact
callback, after checking inherited behavior and all listener-type dispatch sites.
For objects that require only contact-cleared notification, choose a deliberate
optional-callback contract; do not make every solid object implement a no-op.
Remove unused casts in related provider methods while touching them.

**Why:** this is interface segregation with a measurable reduction in boilerplate
and concrete-player coupling. No new abstract object superclass is needed.

**Risk/verification:** medium. A cast can throw, and removing an interface can
change dispatch, so this is not an unconditional bulk deletion. Check clear-only
listeners, inherited overrides, API exposure and representative solid contact,
carrying and rewind behavior. The 84 are review candidates, not 84 proven removals.

### 2. Converge the remaining S2 sprite mapping and DPLC decoders

**Evidence:** `Sonic2PlayerArt.java:168` and `Sonic2DustArt.java:91` duplicate
8-byte mapping-piece parsing. Their DPLC loops at `:208` and `:127` also repeat.
`S2SpriteDataLoader` already owns S2 mapping parsing but these consumers bypass it.

**Proposal:** establish that loader as the S2 format boundary; route player and
dust art through shared decoding. Keep the public player-art DPLC entry point
as a delegate where callers depend on it. Share decoded entry mechanics across
games only where that produces a smaller implementation.

**Why:** art consumers should describe which asset they need, rather than each
owning ROM table traversal and bit extraction.

**Risk/verification:** medium. The existing shared loader supports signed offsets,
null frame zero and priority bits, whereas the local copies differ. Compare the
actual player/dust tables before replacing them; any behavior correction must be
identified separately. Preserve S1 byte versus S3K word DPLC entry counts and the
distinct S3K object-DPLC encoding. Verify decoded frames, DPLC requests, bank sizes,
priority and player/dust rendering against ROM-backed tests.

### 3. Share results-screen numeric tile writing

**Evidence:** `ResultsScreenObjectInstance.java:418` and
`Sonic1ResultsScreenObjectInstance.java:729` duplicate four-digit division,
leading-zero blanking, two-tile digit copying and bounds handling. S1's adjacent
score writer repeats similar mechanics with different width and clamping.

**Proposal:** introduce a small numeric-pattern writer using the caller's ROM
digit patterns and blank tile, destination range and digit width. Start with the
identical four-digit path; add score support only with explicit overflow policy.

**Why:** decimal formatting and tile copying are presentation responsibilities,
separate from results tally state, timing and transitions.

**Risk/verification:** low to medium. Preserve zero display, blanking, oversized
values, partial buffers and GPU dirty ranges. Add focused output-pattern cases
and run affected results ROM-mapping/render tests. Do not merge results state
machines or change tally/audio cadence.

### 4. Share monitor-icon drawing through the existing monitor boundary

**Evidence:** `MonitorContentsObjectInstance.java:66` and
`Sonic1MonitorPowerUpObjectInstance.java:82` duplicate renderer/sheet lookup,
frame validation, first-piece selection and drawing. Both already extend
`AbstractMonitorObjectInstance`.

**Proposal:** use one monitor-icon draw operation in existing monitor support,
passing the resolved mapping frame and position. Keep visibility admission in
each caller: S2 checks `iconActive`, while S1 currently does not. Preserve their
different frame offsets (1 versus 2), subtype masks and expiration mechanisms.

**Why:** reduces mapping knowledge in gameplay objects without adding inheritance
or combining separately scheduled monitor shells and contents.

**Risk/verification:** low to medium. Test icon frame selection, missing art and
inactive/destroyed visibility, then existing monitor and rewind regressions.

### 5. Use one implementation for identical SBZ/FZ scroll mechanics

**Evidence:** `SwScrlSbz.java:39` and `SwScrlFz.java:44` have matching initial
camera setup, delta integration, fixed-point state and uniform scroll filling.
`Sonic1ScrollHandlerProvider.java:46–47` constructs both. FZ's source explicitly
identifies the shared ROM `Deform_SBZ2` path.

**Proposal:** give both routes separate instances of one narrowly named scroll
implementation, or share a small stateful collaborator if class identity must
remain. Keep provider routing and independent runtime state.

**Why:** a zone name should not require a copy of the same algorithm.

**Risk/verification:** medium. Preserve initial rounding, incremental subpixels,
negative camera deltas and reset/rewind ownership. Verify complete packed scroll
buffers and BG camera values for both routes. A missing SBZ-specific effect, if
found, is separate zone work and should not be silently introduced by this refactor.

### 6. Resolve the unused hurt/death radius extraction

**Evidence:** `AbstractPlayableSprite.java:2879` calls
`PlayableHurtRadiusTransition.apply`. `PlayableResetOnFloorRadiusTransition`
contains equivalent hurt handling plus a death entry point, but the source
reference search found no callers. `applyDeath` at `AbstractPlayableSprite.java:3029`
currently calls `setRolling(false)` directly.

**Proposal:** choose one live hurt-radius owner and remove the unused duplicate.
The smallest change is to retain today's hurt implementation and remove dead
support; alternatively use the shared helper for hurt after proving equivalence.
Do not wire its death entry point as cleanup: that could alter gameplay.

**Why:** unused abstractions are another form of duplication and can mislead
future fixes into updating code that never executes.

**Risk/verification:** medium. Verify rolling/standing hurt, S2 sidekick split
radii, reverse gravity, ceiling angles and subpixel preservation. Investigate
whether death needs different behavior only as a separately evidenced bug fix.

### 7. Replace hand-transcribed ring mappings with ROM decoding

**Evidence:** `Sonic1RingArt.java:81` and `Sonic3kRingArt.java:89` construct the
same eight mapping frames in Java. Both describe their data as hardcoded copies
of disassembly mapping files; only the pattern pixels come from ROM here.

**Proposal:** locate and verify each ROM mapping address, decode with its proper
format, and adapt decoded pieces to `RingFrame`. Keep frame timing and ring
behavior in their current owners. Share the mapping-to-ring conversion rather
than moving the copied tables into a common Java constant.

**Why:** directly addresses overly literal transcription and aligns mapping asset
ownership with the ROM pipeline. This is broader than pure deduplication.

**Risk/verification:** medium. Address verification is still required; this review
did not inspect disassembly offsets. Compare all spin/sparkle frames, dimensions,
flips and effective palette lines with verified ROMs. Preserve the S3K pattern
count cap and account for the additive sheet/piece palette convention. Keep any
discovered parity change explicit rather than calling it behavior-neutral.

### 8. Give CNZ cutscene objects the existing coarse range predicate

**Evidence:** `CutsceneKnucklesCnz2AInstance.java:380` and
`CutsceneKnucklesCnz2BInstance.java:341` repeat activation and deletion gates.
Their deletion arithmetic uses `$FF80`, camera minus `$80`, unsigned word
subtraction and `> $280`, already encapsulated by `ObjectRangeOps.outOfRangeX`.

**Proposal:** reuse that predicate for deletion and retain each cutscene's
activation rectangle and lifetime action locally. Review nearby copied predicates
for the same semantic match, without generalising all visibility checks.

**Why:** objects can express an offscreen lifetime decision without repeating
register-width arithmetic. The existing helper has a clear, documented contract.

**Risk/verification:** low to medium. Check exact equality at `$280`, wraparound,
left-of-window values, camera masking and respawn-bit clearing. Render-last-frame
visibility, widescreen-aware bounds and activation ranges remain distinct concepts.

### 9. Separate SMPS header parsing from partially constructed objects

**Evidence:** `AbstractSmpsData` calls overridable `parseHeader()` in its
constructor. S1 and S3K parsers repeat FM and PSG entry loops at
`Sonic1SmpsData.java:91–132` and `Sonic3kSmpsData.java:103–133`.
They also reach overridable `read16` while construction is in progress.

**Proposal:** parse header data with an explicit format/byte-order decoder and
install a parsed result during construction. Share entry decoding rather than
sequencer behavior. Keep S1 relative big-endian and S3K little-endian/bank voice
resolution semantics explicit.

**Why:** removes a fragile Java construction pattern and duplicated parallel-array
population. No current construction failure is claimed by this review.

**Risk/verification:** medium to high. `AbstractSmpsData` is `@ModApi`; design the
compatibility path before changing constructors or protected extension hooks.
Malformed-input behavior also differs: the S3K FM loop checks two available bytes
then reads four, while S1 checks four. Treat any bounds fix separately. Verify
endian parsing, empty/truncated headers, signed offsets, voice resolution,
snapshot immutability and affected audio parity fixtures. Leave chip cores alone.

### 10. Share slot-machine GPU draw/resource ownership

**Evidence:** `CNZSlotMachineRenderer.java:430` and
`S3kSlotMachineRenderer.java:250` duplicate uniform upload, VAO/VBO drawing,
attribute toggling and GL state restoration. Both also own similar location
caches and resource setup. The CNZ path includes viewport-origin uniforms that
are absent from the inspected S3K draw block.

**Proposal:** extract a slot-window GPU pass with explicit shader bindings,
viewport inputs and resource lifetime. Each game supplies faces, offsets, texture
and palette selection. Reuse existing quad support only if vertex-shader contracts
match: `QuadRenderer` uses `gl_VertexID`, whereas these paths bind a position attribute.

**Why:** renderer lifecycle and draw-state handling belong to one rendering owner,
not copies attached to separate game mechanics.

**Risk/verification:** medium to high. Preserve shader differences initially;
do not silently fix viewport behavior in the extraction. Validate both games under
native OpenGL, resize/letterbox conditions, context reset, cleanup and subsequent
draw state. Existing S3K slot renderer tests are a starting point, not sufficient
evidence for both renderers.

### 11. Share strict timing-file field decoding, keeping contracts separate

**Evidence:** `HardwareTimingStreamLoader.java:151–213` and
`HardwareTimingInterstitialStreamLoader.java:219–282` duplicate text/integer/
ordinal validation, boundary/kind conversion, strict UTF-8 decoding and errors.

**Proposal:** a package-private strict field reader shared by the two loaders.
Keep their accepted records, ordering, identity checks, filenames and stream
contracts in separate loaders. Do not generalise runtime timing admission.

**Why:** malformed-input handling can have one implementation without coupling
the two stream schemas.

**Risk/verification:** medium; lower priority than the gameplay/presentation
cleanups. Preserve exact integer acceptance, nonnegative long ordinals, malformed
UTF-8 rejection and path/line diagnostics. Run both loader rejection cases and
timing contract guards. This provides no new authority to trace data.

## Suggested sequence and limits

Begin with 3, 4 and 8: small, established semantic boundaries. Follow with 1, 2,
5 and 6 after dispatch/format/lifecycle review. Treat 7 as a ROM asset migration,
9 as an API-aware construction change and 10 as a native graphics change, each
in a separate reviewable commit. Item 11 is optional lower-priority consolidation.

Do not reopen the first pass's broad controller work solely because files remain
large. Do not merge different routines based only on similar assembly or code
text. Preserve operation order, masks, clocks, allocation/RNG behavior and rewind
state; semantic Java names and focused owners are the intended improvements.

After approval, load the relevant implementation/disassembly/art skills per item
and follow the documentation checklist and commit hooks. Work directly on
`develop` unless the user changes that instruction. This plan alone makes no
runtime changes and requires no engine-suite run.

For implementation validation, pin the actual pre-change integration commit,
finish focused fixes and documentation, and run category-runner `--preflight` in
the real launch environment. Before a broad run, report the selected class count,
cost and stop rule. Budget one completed required selection per candidate; the
previous broad normalisation measured approximately 24 minutes ordinary plus
10 minutes guards. Preserve the runner's timeout/repeat controls and known
working native macOS access. Use focused matched attribution for disputed
failures, not repeated full suites. Report remaining failures and skips honestly.
Affected traces and the mandatory S3K loading/bootstrap checks remain required
where applicable; do not rerun previous unchanged evidence just for this review.

## Implementation record

### Candidate 3 — results numeric patterns

Shared the identical four-digit bonus path in `NumericPatternWriter`. Score
formatting retains its separate clamping policy and reuses only digit copying.
Overflow, partial-pair handling (including leading-blank bounds exceptions),
zero display and GPU dirty-range ownership are preserved.
Validation base: `1cd1175ecdaaafce89a4bdd86287a7794b6a1b9a`.

Validation on the candidate working tree:
- Focused results/mapping/PLC/widescreen checks: 24 tests, no failures/errors/skips,
  using absolute S1 and S2 ROM properties.
- `run_categories.py --base 1cd1175ecdaaafce89a4bdd86287a7794b6a1b9a --run
  --repeat-reason "New pass-2 candidate 3 ..."`, with native macOS access,
  Lua 5.4.8 and PowerShell: run `20260912T161020Z-9b1c641b` selected 2,167
  classes; ordinary completed 16,594 tests (17 failures, 5 errors, 71 skips)
  in 486 seconds; guards completed 656 tests (1 failure, no errors/skips)
  in 565 seconds. This is partial category validation, not a green suite.
- The new architecture violation was corrected by placing the writer in
  `level.render`, not the lower-level `graphics` package. Focused
  `mvn -Dmse=off -Pguards -Dtest=TestArchUnitRules test`: 29 tests passed,
  no skips. The relocated writer's three output tests also passed.
- Matched baseline/current runs of `TestDisplayAspectResolution`,
  `TestObjectPlacementEncoding`, and `TestSonic2LivesHudDonation` each ran
  10 tests with the same 3 failures and no errors/skips. Baseline was detached
  `1cd1175e` at `.worktrees/normalisation-pass2-baseline`, with identical
  absolute ROM properties and native access. These failures predate this change.
- Other ordinary failures cover FBZ routes/matrix, missing `s3k.gen` donor setup,
  and GL 2.1 capture helpers compiling 4.1 shaders. They were not newly
  baseline-attributed in this candidate. Skips were inspected: legacy ROM
  filenames, opt-in benchmarks/captures, unavailable EGL/reference data, and
  one spin-tube capture assumption. No trace frontier was measured or changed.
- Consumed category diagnostics were acknowledged and removed; focused logs
  were removed after inspection. The initial runner refusal reused no results:
  its prior broad-attempt receipt belonged to earlier work, and the accepted
  invocation explicitly recorded this candidate's new scope.

### Candidate 4 — monitor icon drawing

Both monitor-content objects now call `AbstractMonitorObjectInstance.drawMonitorIcon`.
The callers retain frame offsets, subtype masks, visibility admission and expiration.
The shared operation owns sheet/renderer admission and first-piece drawing only;
no new mutable state or rewind shape is introduced. Focused tests cover differing
inactive visibility, destroyed objects, frame selection and missing art.
Validation base: `446a10ffc4` (candidate 3).

Validation on the candidate working tree:
- Focused icon, monitor, ROM-art and live rewind checks: 19 tests passed, no skips,
  with absolute S1/S2 ROM properties.
- Java 21/Lua 5.4.8/PowerShell preflight passed. Native macOS run
  `run_categories.py --base 446a10ffc4 --run --repeat-reason
  "New pass-2 candidate 4: shared monitor icon drawing with visibility, ROM-art,
  and rewind tests; previous completed selection covered candidate 3."` selected
  all 2,506 ordinary classes. Run `20260912T163441Z-70e4d808`: ordinary
  20,373 tests, 29 failures, 8 errors, 97 skips (1,362 seconds); guards
  656 tests passed without skips (566 seconds). The ordinary suite is not green.
- Failures include the three previously matched baseline failures, FBZ routes,
  donor-ROM lookup, GL 2.1 capture helpers, sample packaging, hook policy, and
  macOS shell/secure-directory audio tooling. The additional failures were not
  baseline-attributed here; none was reported by the focused monitor checks.
- Inspected skips cover legacy ROM paths, absent audio/reference data, optional
  capture/benchmark/soak measurements, EGL, and the spin-tube assumption.
  Category diagnostics were acknowledged after inspection. No trace frontier changed.
