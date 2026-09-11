# Unfinished-Code Remediation — Human Review Report

Date: 2026-08-08

## Review branch

- Branch: `feature/ai-unfinished-remediation-review`
- Worktree: `.worktrees/unfinished-remediation-review`
- Updated base: `develop` at `228e2effa`
- Delivery state: local and unpushed; not merged into `develop`

The branch is intentionally retained for human review. It does not delete
coherent unfinished features merely because they are incomplete. Where an
incorrect temporary implementation was removed, its behavior was replaced by
the disassembly-owned topology and covered by tests.

## Integrated outcomes

### Route and object behavior

- LRZ1 restores the non-Knuckles falling-introduction state owned by
  `SpawnLevelMainSprites loc_68A6`. Saved returns are gated without suppressing
  LBZ1's buried-start launch. The earlier SSZ attribution was disproved and the
  current docs now say so.
- The AIZ Knuckles miniboss napalm attack now uses the three native barrel
  children, harmful FallingShots, ROM-backed art/mappings, native explosion
  timing, and graph rewind. The artificial central controller was removed only
  after the disassembly proved that the barrel children own this behavior.
- AIZ2 end-boss emerge/re-submerge now creates the subtype 0/2 waterfall splash
  children with native allocation, init/draw boundaries, palette, animation,
  and rewind behavior.

### Honest capability and ownership boundaries

- `SpecialStageDebugCapabilities` makes provider support explicit. S1 keeps
  direct movement, S2 keeps its full debug tools, and S3K keeps X/Z navigation.
  Unsupported provider actions no longer silently mutate stage state or create
  rewind boundaries. Shared global overlay/screenshot key meanings remain
  documented and active.
- S1 background Y is confirmed to be owned by the eight zone scroll handlers,
  including same-instance rewind recomputation. The legacy runtime query and
  ignored renderer hint were removed; compatibility overloads are explicitly
  equivalence-tested.
- The unused `AbstractLevel.markAllDirty()` no-op was removed. Geometry restore
  invalidation remains owned by `LevelRewindSnapshotAdapter` and persistent
  Plane B restoration remains owned by `LevelTilemapRewindAdapter`.
- S3K SMPS fixed-point traversal proves `FF 01/02/03` unreachable in all
  loader-supported S&K/S3 music and S&K-loader SFX streams plus both native
  SFX banks (`33-DF`, 173 entries each). Strict full-bank traversal resolves
  every native root/frontier; ROM type-check bytes prove S&K `DC` is CreditsK
  music and `DD-DF` are SFX, while S3 dispatches `DC-DF` as SFX. The differing
  `9B`/`AD` payloads and all alias targets are covered. Operand alignment
  remains supported for custom streams.
- Master-title navigation, confirmation, and error audio now use typed
  host-owned cues and deterministic ROM-independent PCM. The presentation
  backend is installed before the pre-ROM title and, after title exit/reset,
  recreates exactly one closed/replaced presentation sink before re-entry;
  interaction, PCM, and lifecycle tests cover the complete path.

### S2 capability boundaries (CPZ debug placement and human-P2 monitors)

The two remaining audited S2 ambiguities have separate dispositions. Native
S2 `Debug_placement_mode` is a ROM-global mode entered through
`Debug_mode_flag` plus B (`s2.asm:36224-36230`), not the engine's D free-fly
movement. CPZ Obj1E now rejects a player in the supported engine debug mode at
the same entry boundary that shared touch/solid controllers use. This prevents
debug movement from being captured without pretending that native ring/item
placement exists. `DebugModeProvider.hasLevelDebug()` remains false for S2.

Native human-P2 monitor behavior is not an object-local omission. S2's
`Touch_Monitor` branch (`s2.asm:85337-85340`) depends on the ROM-global
`Two_player_mode`, but the engine has no S2 competition-mode owner or human-P2
slot. Player 2 bindings remain sidekick/manual-input paths.
`MonitorObjectInstance` was therefore left unchanged beyond its existing
ROM-faithful lead-player/CPU-sidekick guard.

Focused JDK 21 validation for this boundary:

```text
mvn -Dmse=off -Dtest=com.openggf.game.sonic2.objects.TestCPZSpinTubeObjectInstance test
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0

mvn -Dmse=off -Dtest=com.openggf.game.sonic2.TestSonic2SpecialStageModuleGraph test
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
```

The first run was intentionally red before the CPZ guard (the new debug-mode
capture assertion failed); it passed after the source-backed guard was added.
Existing `TestMonitorObjectInstance` sidekick tests remain the evidence for the
supported one-player path. Native ring/item placement and human-P2 monitor
parity remain deferred until an engine-wide S2 debug-placement/competition
capability has an owner, initialization contract, and ROM-backed route tests.
No title-provider assertion is treated as evidence that competition mode is
absent.

## Independently rejected work

Two Big Arm attempts were rejected and left unintegrated:

1. The first committed candidate on `bugfix/ai-remediate-lbz-big-arm`
   (`98d968d7f`) invented phases, mapping ownership, and defeat/capsule flow;
   independent review found no native articulated arm/grab graph.
2. A second v2 working-tree attempt was never committed. It did establish
   useful ROM-shaped articulated anchors/tables (`$AD`/`$9A`), grab, and debris,
   and passed six focused tests plus 28 graph/rewind guards. It did not prove
   the root choreography, post-capsule continuation, or a Knuckles LBZ route
   trace, so it was not integrated.

The inert placeholder therefore remains the honest P0 blocker. The v2 test
counts are supporting evidence for a future port, not proof of a playable boss.

## Workspace-isolation incident

During the load-profile documentation lane, four task-specific unstaged hunks
appeared in the main `develop` worktree at `5922ee722`. They were
byte-identical to, or overlapped, the isolated load-profile commit. The root
worker preserved them and never restored or staged them; they are not part of
this review-branch proof. This report therefore does not claim that main was
untouched; the remediation evidence below is branch-local and must be
integrated and revalidated separately.

## Validation

All commands ran under Maven's JDK 21 with the canonical project-root Sonic 1
REV01, Sonic 2 REV01, and locked-on S3K ROM properties.

Combined focused gate:

```text
Tests run: 93, Failures: 0, Errors: 0, Skipped: 2
```

This includes LRZ/LBZ bootstrap and saved-return behavior, AIZ napalm route and
rewind, AIZ2 splash graph rewind and art, SMPS reachability/operand alignment,
special-stage capability routing, all eight S1 scroll handlers, and renderer
source guards. The two skips are the existing environment-dependent renderer
sampling cases.

The native SFX extension was run separately on JDK 21 with the verified locked-on
ROM:

```text
mvn -Dmse=off \
  -Dtest=com.openggf.game.sonic3k.audio.smps.TestSonic3kSmpsMetaCommandReachability \
  -Ds3k.rom.path="<locked-on S3K>" test
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

That test covers both 173-entry native tables, all 346 native entries, and the
strict full-bank frontier/dispatch assertions described in the audio research
note.

The corrected rewind inventory gate also passed 32 focused tests after adding
`AizEndBossWaterfallChild` to the intentional graph-covered totals.

The dead S3K special-stage results-offset row is resolved by caller-free cleanup
commit `e0223ed06`: it removed only the six unverified results-art constants and
size constants from `Sonic3kSpecialStageRomOffsets`. The live manager-local
alignment/debug hooks, explicit capability profile, and verified results path
remain; this cleanup does not claim that those live capabilities were deleted.

## Mecha Sonic parity follow-up

On 2026-08-08, branch `bugfix/ai-remediate-mecha-order` (based on reviewed
remediation HEAD `5cc94d457`) closed the audited Sonic 2 ObjAF movement-order
debt. The implementation follows shipped REV01 `loc_398C0`/`loc_39D4A` (the
audit's provisional `loc_398F4`/`loc_39D44` labels resolve to those source
labels): phase logic runs first, the LED and targeting sensor align, and one
outer-loop `ObjectMove` runs afterward. Child updates no longer overwrite those
pre-move positions. The existing Mecha Sonic and Death Egg Robot implementations
are covered by the dedicated DEZ ending replay at
`src/test/resources/traces/s2/dez_ending/`; its auxiliary stream shows ObjAF
present from frame 127 through the fight. With verified Sonic 2 REV01 ROM and
`-Ptrace-replay -Dmse=off -Dsurefire.forkCount=1
-Dsurefire.runOrder=alphabetical`, the dedicated
`TestS2DezEndingLevelSelectTraceReplay#replayMatchesTrace` passed 1/1 on both
base `5cc94d457` and candidate `4b4572cc3`. The complete-run fixture remains at
`src/test/resources/traces/s2/runs/s2-sonic-tails-complete-emeralds/seg28_dez1/`.
No trace frontier advanced. JDK 21 focused validation also passed:

```text
mvn -Dmse=off -Dtest=com.openggf.tests.TestDEZMechaSonic test
Tests run: 30, Failures: 0, Errors: 0, Skipped: 0

mvn -Dmse=off -Dtest=com.openggf.tests.TestDEZMechaSonic,\
com.openggf.game.sonic2.objects.bosses.TestS2MechaSonicGraphRewind,\
com.openggf.game.rewind.TestBossChildNoDoubleSpawnParity,\
com.openggf.game.rewind.coverage.TestRewindCoverageGuard test
Tests run: 40, Failures: 0, Errors: 0, Skipped: 0
```

The first run was intentionally red before the production edit (27 tests, one
dash-start movement failure). The dedicated DEZ replay was green on both base
and candidate, so this change introduced no replay regression.

Deterministic full-suite command for both current `develop` and the feature:

```bash
mvn -Dmse=off -Dsurefire.forkCount=1 -Dsurefire.runOrder=alphabetical \
  -Dsonic1.rom.path=<Sonic 1 REV01> \
  -Dsonic2.rom.path=<Sonic 2 REV01> \
  -Ds3k.rom.path=<locked-on S3K> clean test
```

| Run | Total | Pass | Failure | Error | Skipped |
|---|---:|---:|---:|---:|---:|
| `develop` `228e2effa` | 14,342 | 14,263 | 34 | 14 | 31 |
| remediation branch | 14,404 | 14,325 | 34 | 14 | 31 |

Normalized Surefire comparison by class, invocation name, outcome, and
exception type found identical failure/error sets and no baseline
PASS-to-FAIL/ERROR transition. The feature adds 64 passing invocations,
including the title-audio/sink-lifecycle, Mecha Sonic DEZ-order, and S2
capability-boundary coverage. Two obsolete scalar-only napalm probe invocations
disappear because the projectile is now parent/sibling graph-linked; their
replacement graph classification and real `ObjectManager` rewind tests pass,
for a net increase of 62 rows. Raw-message-only differences are one volatile
launcher identity and six Java helpful-NPE messages on already-erroring Tornado
tests.

## Still open

Highest-priority remaining work is deliberately visible rather than deleted:

1. Port Big Arm from the native object graph and defeat flow, using only the
   v2-proven anchors/tables (`$AD`/`$9A`), grab, and debris where they survive
   source review; then prove the root choreography, post-capsule continuation,
   and Knuckles LBZ route.
2. Capture route/trace evidence for the AIZ napalm and AIZ2 splash changes.
3. Continue the roadmap's load-profile semantics work.
4. If native S2 debug placement is made a product goal, design its engine-wide
   global mode owner (ring/item placement, object lifecycle, and level-wide
   gates) before extending CPZ beyond the current free-fly boundary.
5. If S2 competition/human-P2 parity is made a product goal, add a dedicated
   mode owner for the second player, initialization, physics, camera, scoring,
   and competition-zone lifecycle before implementing the monitor branch.

## Follow-up: master-title audio disposition

The Wave 3 title-menu audio item is resolved on the remediation follow-up
branch. `MasterTitleScreen` now emits typed host-owned `NAVIGATE`, `CONFIRM`,
and `ERROR` cues through an injected sink. `Engine` connects that sink to the
existing `AudioManager` command timeline and initializes the normal LWJGL
presentation backend before the bootstrap title is shown. Because the title
screen runs before ROM/profile selection, the presentation source factory
resolves these names to deterministic synthesized PCM (`host/ui/*`) rather
than asking a game SMPS loader for a fabricated SFX id. The same stable asset
identity can be regenerated during presentation snapshot restore.

Focused JDK 21 validation:

```text
mvn -Dmse=off -Dsurefire.forkCount=1 \
  -Dtest=com.openggf.game.TestMasterTitleScreenAudio,com.openggf.audio.presentation.TestHostUiSfx test
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

The interaction tests cover one navigate, one confirm, one missing-ROM error,
selection-boundary silence, repeated confirm suppression, and repeated
load-error suppression. The PCM test resolves all three host cues, proves
stable identity reuse for each immutable sample, distinguishes their content,
and mixes each cue into nonzero output.

The lifecycle follow-up also passed:

```text
mvn -Dmse=off -Dsurefire.forkCount=1 \
  -Dtest=com.openggf.audio.TestAudioManagerRuntimeInstallation,com.openggf.TestEngine test
Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
```

That gate proves `AUDIO_ENABLED=false` performs no install, enabled startup
installs once, and the title-exit reset followed by gameplay initialization
asks the retained backend to recreate exactly one closed/replaced presentation
sink. Re-entry then presents a cue through the rebuilt sink without replacing
the backend.

The original audit remains the complete disposition ledger; this report records
only what this remediation branch changed or deliberately rejected.
