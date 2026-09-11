# Dead and unfinished code audit

**Date:** 2026-08-08
**Scope:** reachability and explicit unfinished-code evidence before the source sweep.

## Method and classification

The repository contains 2,312 tracked production Java files. The audit ran:

```bash
git ls-files 'src/main/**/*.java' | wc -l
for candidate in DebugSpriteMovementManager NoOpPresenceClient S3kSavePayload Sonic2SpecialStageDebugProvider SpindashCameraTimer VelocityAnimationProfile DebugArtViewer Sonic3kSpecialStageScalars DebugColorShaderProgram DebugPrimitiveRenderer CNZBossAnimations; do
  git grep -n -w -- "$candidate" || true
done
rg -n -i --glob 'src/main/**' --glob 'tools/**' \
  '(TODO|FIXME|\bstub(bed)?\b|\bscaffold\b|not (yet )?implemented|\bno-op\b|Phase [0-9]+)'
```

`git grep` is an exact-word search over tracked files. Its scope includes source,
tests, resources, tooling, scripts, root launchers, `.github`, `.mvn`, Maven and
configuration files, current docs, `META-INF/services`, and Graal configuration.
Search classifications are **declaration**, **current evidence**, **historical
artifact**, and **live reachability**. Marker dispositions are **dead cleanup**,
**stale cleanup**, **ranked unfinished work**, **intentional contract**,
**generated output**, **historical/tooling note**, **unsupported broader game
mode**, and **ambiguous ownership retained**.

At the pre-mutation audit commit `345fa27c2858b070f7953d3709868ba4697cabe1`,
the marker scan found 622 hits in 222 files. Its complete line-level inventory
is [`2026-08-08-dead-unfinished-marker-inventory.tsv.gz`](evidence/2026-08-08-dead-unfinished-marker-inventory.tsv.gz).
Every row has `path`, `line`, escaped matched text, classification category, and
disposition. It is a lexical inventory, not a dead-code analyser: a marker can
describe a ROM phase, a default contract, or a historical/tooling fact.

The inventory is the command-above output transformed deterministically into
those five columns by ordered ownership rules: generated scaffold, three stale
comments, duplicate results offsets, ranked P0–P3 owners, unsupported CPZ/2P
owners, ambiguous S1/dirty-region owners, explicit ROM/default contracts, then
historical/tooling residue. Validate that pinned inventory with:

```bash
gzip -dc docs/architecture/audits/evidence/2026-08-08-dead-unfinished-marker-inventory.tsv.gz | wc -l
gzip -dc docs/architecture/audits/evidence/2026-08-08-dead-unfinished-marker-inventory.tsv.gz | awk -F'\t' 'NF != 5 { exit 1 }'
```

These checks report 622 rows and exit zero. The inventory has 516
intentional-contract rows, 32 ranked-unfinished rows, 28 historical/tooling
notes, 24 generated-output rows, 12 stale-cleanup rows, four unsupported-mode
rows, three dead-cleanup rows, and three ambiguous-ownership rows.

The same scan on the post-sweep tree reports 615 rows. To reproduce both
snapshots without changing branches, extract the pinned commit to a temporary
directory and compare normalized `path:text` rows so unrelated source line
movement cannot masquerade as marker churn:

```bash
SCAN_TMP=$(mktemp -d /tmp/openggf-marker-scan.XXXXXX)
git archive 345fa27c2858b070f7953d3709868ba4697cabe1 src/main tools | tar -x -C "$SCAN_TMP"
(cd "$SCAN_TMP" && rg -n -i --glob 'src/main/**' --glob 'tools/**' \
  '(TODO|FIXME|\bstub(bed)?\b|\bscaffold\b|not (yet )?implemented|\bno-op\b|Phase [0-9]+)' | sort) > /tmp/openggf-markers-pre.txt
rg -n -i --glob 'src/main/**' --glob 'tools/**' \
  '(TODO|FIXME|\bstub(bed)?\b|\bscaffold\b|not (yet )?implemented|\bno-op\b|Phase [0-9]+)' | sort > /tmp/openggf-markers-post.txt
wc -l /tmp/openggf-markers-pre.txt /tmp/openggf-markers-post.txt
sed -E 's/^([^:]+):[0-9]+:/\1:/' /tmp/openggf-markers-pre.txt | sort > /tmp/openggf-markers-pre-normalized.txt
sed -E 's/^([^:]+):[0-9]+:/\1:/' /tmp/openggf-markers-post.txt | sort > /tmp/openggf-markers-post-normalized.txt
comm -23 /tmp/openggf-markers-pre-normalized.txt /tmp/openggf-markers-post-normalized.txt
comm -13 /tmp/openggf-markers-pre-normalized.txt /tmp/openggf-markers-post-normalized.txt
```

The counts are exactly 622 before and 615 after. The normalized comparison has
exactly seven removed rows and no additions: the MCZ stale TODO, the spring
“currently stubbed” claim, three deleted unverified results-offset TODOs, and
the two completed sidekick “Stubbed in Task 2” paragraphs. The 622-row artifact
therefore remains point-in-time classification evidence; it is not claimed as
a scan of the current source tree.

## Reachability evidence

| Type | Exact-match classification | Disposition |
|---|---|---|
| `DebugSpriteMovementManager` | Declaration; 2026-07-29 design is historical. | Delete: no caller, registry, service entry, reflective name, launcher, test, or supported contract. |
| `NoOpPresenceClient` | Declaration; Discord plan is historical. | Delete: no construction, service loader, configuration, or supported fallback. |
| `S3kSavePayload` | Declaration only. | Delete: no save serialization or data-select caller. |
| `Sonic2SpecialStageDebugProvider` | Declaration and constructors only. | Delete: no provider registration or debug shortcut selects it. |
| `SpindashCameraTimer` | Declaration; runtime-container review is historical. | Delete: no timer registration, factory, configuration, or caller. |
| `VelocityAnimationProfile` | Declaration only. | Delete: no animation owner constructs it. |
| `DebugArtViewer` | Declaration plus historical designs/plans; no live source, test, launcher, Maven-exec, resource, or current user-tool reference. | Delete as abandoned scratch CLI. It hard-codes `s2.gen` and directs users to edit `Sonic2ObjectArt`. |
| `Sonic3kSpecialStageScalars` | Declaration only. | Retain: coherent Blue Sphere projection requires an accuracy/integration decision. |
| `DebugColorShaderProgram` | Declaration plus historical changelog evidence. | Retain with the collision/sensor debug-rendering path. |
| `DebugPrimitiveRenderer` | Declaration plus historical changelog evidence. | Retain with `DebugColorShaderProgram`; unwired is not obsolete. |
| `CNZBossAnimations` | Declaration only. | Retain: ROM-derived scripts need dedicated proof that live CNZ boss code duplicates every command. |

`git log --all --follow --oneline -- src/main/java/com/openggf/debug/DebugArtViewer.java`
was run. Non-checkpoint commits are default-ROM-filename, BufferedImage/AWT,
logging, package-move, and tidy changes. They are incidental maintenance, not a
documented invocation contract or maintained CLI.

Retain `shader_debug_text.vert` and `.frag` with unwired debug rendering.
`GraphicsManager` directly loads `shader_debug_color.glsl` and `.vert`, so both
are live. No debug shader is deleted.

## Narrow cleanup contracts

| Owner | Symbol | Disposition |
|---|---|---|
| `DisassemblySearchResult` | `hasBinclude()` | Delete caller-free deprecated alias. |
| `TraceReplayBootstrap` | `usesS2TornadoRideStartForTraceReplay(TraceData)` | Delete caller-free alias; `TestBuildToolingGuard` deliberately keeps a literal that bans new use. |
| `TraceMetadata` | `hasPerFrameCnzSlotMachineState()` | Delete caller-free alias; retain `hasPerFrameSlotMachineState()` and its guard literal. |
| `InitialProcessSpritesLevelManagerBase` | `consumePendingInitialObjectSetupPass()` | Delete caller-free `forRemoval` alias. |
| `CnzMinibossInstance` | `setLower2CounterForTest(int)` | Delete caller-free `forRemoval` shim and its stale comment. |
| `LevelManager` | protected no-argument constructor | Delete caller-free `forRemoval` constructor that always throws. |
| `Sonic3kSpecialStageRomOffsets` | `ART_KOS_RESULTS_GENERAL`, `ART_KOS_RESULTS_TK_ICONS`, `PAL_RESULTS`, and three matching size constants | Delete only this unused unverified section; live results use verified `Sonic3kConstants` addresses. |

The stale-comment edits are MCZ `boss_hurt_sonic` (harmful drill contact already
owns it in `onTouchResponse(...)`), S3K spring “currently stubbed” (native init
already swaps vertical direction under reverse gravity), and the two completed
sidekick paragraphs headed “Stubbed in Task 2; body lands in Task 4/5”.

`kosinski.txt` and its sole Graal `kosinski\\.txt` include are unused runtime
reference material. Task 2 relocates its content, provenance, and current
decompressor-owner links to
`docs/architecture/research/compression/kosinski-format.md`.

Completed file removal is seven Java files (339 lines) plus the 125-line
misplaced compression document: eight runtime files and 464 original lines.
The compression content was preserved in architecture research. The narrow
member cleanup removed another 70 caller-free source lines; comment corrections
and the single Graal include account for the remaining deletions in the branch
diff.

## Marker inventory classification

| Category | Disposition | Evidence |
|---|---|---|
| Exact obsolete declarations and results constants | Dead cleanup | The seven types and narrow symbols above lack inbound contracts. |
| MCZ, spring, and sidekick comments | Stale cleanup | Existing owners and tests already implement the claimed missing behavior. |
| P0–P3 table below | Ranked unfinished work | Live no-op, approximation, or missing ROM behavior. |
| Default providers, abstract hooks, guarded queue/restore methods, sized-resource overloads, `SK_alone_flag`, camera AIZ ship translation, `FixBugs=0`, test upload sinks | Intentional contract | Deliberate default, guard, or shipped-ROM documentation. |
| `ObjectScaffoldTool` TODO vocabulary | Generated output | Generator content, not a runtime claim. |
| Historical task/phase prose, changelogs, ROM phase labels, signpost stub-art labels | Historical/tooling note | Lineage or ROM terminology only. |
| `NoOpSpecialStageProvider`, S1/S3K debug hooks, CPZ debug placement, human-P2 monitors | Intentional contract or unsupported broader game mode | Provider defaults are explicit. CPZ now honors the supported engine free-fly boundary while native placement remains unavailable; human-P2 competition still needs an engine-level mode design. |
| `Sonic1.getBackgroundScroll()` and `AbstractLevel.markAllDirty()` | Resolved | The runtime no longer queries S1's zero-return compatibility API. S1 zone scroll handlers own background Y and are recomputed from restored camera/frame state through `ParallaxManager`; the shared API remains for S2/S3K compatibility. The unused `markAllDirty()` no-op and its unobserved legacy test call were removed; rewind tilemap invalidation remains owned by `LevelRewindSnapshotAdapter` and `LevelTilemapRewindAdapter`. |

Ordinary `Phase N` labels are intentional state-machine descriptions. Explicit
no-op transitions such as invalid routines, resolved collision callbacks, and
drawless controllers are contracts. The scan cannot prove private declarations,
reflection, resource naming conventions, or generated output unused; those are
its intentional heuristic limits.

## Ranked unfinished work

| Rank | Owner | Runtime impact | Required source/test evidence |
|---|---|---|---|
| P0 | `AizMinibossNapalmProjectile` | FallingShot movement/floor handling, harmful touch timing, ROM-backed art/mappings, seven explosion children, and rewind are implemented, but the live Knuckles per-barrel route and slot/lifetime parity remain unproven. | `sonic3k.asm:137451-137581,137836-137925`; focused and production `ObjectManager` route tests; real rewind roundtrip; capture a Knuckles miniboss route proving activation, child ordering, floor impact, and explosion lifetime before resolving. |
| P0 | `LbzFinalBoss2Instance` | Big Arm remains inert, invisible, and persistent, blocking Knuckles LBZ completion. The first committed candidate (`98d968d7f`) invented phases, mapping ownership, and defeat flow; a second uncommitted v2 proved articulated anchors/tables (`$AD`/`$9A`), grab, and debris, but not the root choreography or post-capsule continuation. | Keep the inert placeholder until a ROM-owned port proves the root choreography, post-capsule continuation, rewind, and a Knuckles LBZ route trace. The v2 passed six focused and 28 graph/rewind guards, but those tests do not replace route evidence. |
| P1 (resolved 2026-08-08) | `Sonic3kLevelEventManager` | LRZ1 non-Knuckles omitted native falling-intro state. | Ported `SpawnLevelMainSprites` `loc_68A6`; production-load character/zone/act and checkpoint, big-ring, and bonus-return coverage is in `TestS3kLrzFallingIntroBootstrap`. The semantic saved-state gate runs before every zone branch. Source audit also corrected the stale SSZ attribution: `$A00/$A01` has no `loc_68A6` branch. |
| P1 (resolved 2026-08-08) | `AizEndBossInstance` / `AizEndBossWaterfallChild` | Emerge/re-submerge now allocate and render the native splash child; full trace parity remains to be exercised. | `ChildObjDat_69D2E` subtype 0/2 is ported with ROM assets, production slot allocation, rewind recreation, and owner tests; no end-to-end AIZ2 boss trace was rerun in this change. |
| P1 (resolved 2026-08-08) | `Sonic1.getBackgroundScroll()` | The shared API retains a zero-return S1 compatibility override, but it is not an authoritative runtime path. | Removed the unused background-scroll query and parameter from frame, rewind, and shader paths. S1's per-zone handlers remain the owner and are re-derived after rewind; handler tests plus the source guard cover this boundary. The shared API remains for S2/S3K compatibility; no broad parallax migration was made. |
| P2 (resolved 2026-08-08) | S1 and S3K special-stage providers/managers | Shared debug/alignment shortcuts previously delegated to scaffold/no-op paths. | Added `SpecialStageDebugCapabilities`; `GameLoop`/`Engine` now route only advertised stage-provider controls. S1 advertises direct movement, S3K advertises stage/layout navigation, and unsupported stage actions do not call provider no-ops or consume stage rewind boundaries. Global F1/F3/F4/F12 overlay/screenshot bindings remain shared. `TestSpecialStageDebugCapabilities` and `TestGameLoopSpecialStageRewindDebugBoundary` cover both paths. |
| P1 (resolved 2026-08-08) | `Sonic3kCoordFlagHandler.handleMetaCommand(...)` | `SND_CMD`, `MUS_PAUSE`, `COPY_MEM` had operand-only cases. | Fixed-point ROM-backed inventory covers S&K-loader music/SFX (51 S&K music, 50 S3 music, 169 S&K-loader SFX) plus both native SFX banks (173 entries each, `33-DF`) and reaches none of `FF 01/02/03`. Strict full-bank traversal validates every native root/frontier; IDs `9B`/`AD` use their differing bank payloads, and `DC-DF` all alias the `DB` target. ROM type-check bytes prove S&K `DC` is CreditsK music and `DD-DF` are SFX, while S3 dispatches `DC-DF` as SFX. `EB` is a separate implemented path. |
| P2 (resolved 2026-08-08) | `MasterTitleScreen` | Called navigate/confirm/error methods were silent. | Engine injects typed host-owned cues into the pre-ROM menu; the unified presentation fallback synthesizes deterministic ROM-independent PCM, while boundary, confirm, repeated-error, non-silent PCM, and title-exit sink-recreation tests cover interaction, playback, and the closed/replaced presentation-sink lifecycle. |
| P2 (narrowed 2026-08-08) | `LoadTimeProfileFactory` | `FAST` and `REALISTIC` are retained reserved aliases. Every factory resolution warns; `FAST` returns `LoadTimeProfile.IMMEDIATE` (`NONE`) and `REALISTIC` returns the supplied profiled instance (`PROFILED`). | No authoritative cross-game semantics are available: the profile contract currently accepts only S3K Kosinski queue submissions, while S1/S2 PLC/DPLC timing remains in separate production owners. Keep the modes and exact aliases. Finish only with independently measured service costs, explicit FAST/REALISTIC model semantics, all-game readiness/rewind tests, and trace-authority isolation evidence; do not remove or invent delays. Validation: `docs/architecture/validation/2026-08-08-load-time-profile-remediation.md`. |
| P2 (resolved 2026-08-08) | `AbstractLevel.markAllDirty()` | The public TODO/no-op had no production caller; one legacy test adapter called it without observing an effect. | Removed the placebo API and legacy call. Production `LevelRewindSnapshotAdapter` already detects restored block/chunk/map reference changes and calls `LevelManager.invalidateAllTilemaps()`; `LevelTilemapRewindAdapter` separately restores persistent Plane B state. Existing changed/unchanged-reference and full-rebuild tests cover both ownership boundaries. |
| P2 (resolved 2026-08-08) | `Sonic2MechaSonicInstance` | `ObjectMove` ordering differed from ROM outer attack loop. | Refactored from the audit-cited `loc_398F4`/`loc_39D44` (the shipped source labels are `loc_398C0`/`loc_39D4A`); `TestDEZMechaSonic` covers phase and child order, existing DEZ graph rewind tests remain green, and `TestS2DezEndingLevelSelectTraceReplay#replayMatchesTrace` passes 1/1 on both base `5cc94d457` and candidate `4b4572cc3` with verified REV01 ROM. ObjAF is present from auxiliary frame 127; no frontier advanced. |
| P3 (resolved 2026-08-08) | `Sonic3kSpecialStageManager` results/debug remnants | The six dead, unverified results-art constants were removed; alignment/debug methods remain as manager-local compatibility hooks and are not silently promoted to supported controls. | Commit `e0223ed06` removed only `ART_KOS_RESULTS_GENERAL`, `ART_KOS_RESULTS_TK_ICONS`, `PAL_RESULTS`, and their size constants. The live special-stage manager, capability profile, and verified results path remain; no live capability hooks were deleted. |
| P3 (resolved boundary 2026-08-08) | `CPZSpinTubeObjectInstance` debug placement | Native ROM `Debug_placement_mode` remains unavailable; the supported engine free-fly debug mode can no longer be captured by a tube. Normal tube gameplay is unaffected. | `isDebugMode()` entry guard plus `TestCPZSpinTubeObjectInstance.engineDebugMovementCannotBeCapturedByTube`; `DebugModeProvider.hasLevelDebug()==false` is asserted in `TestSonic2SpecialStageModuleGraph`. Native ring/item placement still requires an engine-wide placement capability; no object-local carve-out. |
| P3 (explicitly unavailable 2026-08-08) | `MonitorObjectInstance` human-P2 | S2 competition/human-P2 monitor behavior remains absent; one-player and CPU-sidekick paths stay separate. No S2 competition-mode owner is wired into level gameplay. | Existing monitor sidekick tests cover the supported path; dedicated S2 competition-mode owner/design is required before implementing `Two_player_mode` behavior. No unconditional title-provider result is treated as proof of mode absence, and no object-local game-mode branch is added. |

## Documentation freshness map

| Finding | Authoritative current document | Correction |
|---|---|---|
| AIZ route and Mecha Sonic parity | `README.md`; `docs/guide/playing/game-status.md` | Qualify completion; name napalm, splash, falling intro, Big Arm, Mecha Sonic ordering; refresh date. |
| AIZ gaps | `docs/architecture/research/s3k-zones/aiz-analysis.md` | Dated current-engine notes for napalm and the resolved splash child, retaining disassembly analysis. |
| Big Arm | `docs/architecture/research/s3k-zones/lbz-analysis.md` | Keep the inert/invisible P0 blocker explicit and record both rejected ports: `98d968d7f` and the uncommitted v2 evidence/limits. |
| Falling initialization | `docs/architecture/research/s3k-zones/lrz-analysis.md`; `docs/architecture/research/s3k-zones/ssz-analysis.md` | LRZ1 non-Knuckles is implemented and tested; SSZ is corrected to record that `$A00/$A01` is not a `SpawnLevelMainSprites` `loc_68A6` gate. |
| Special-stage debug keys | `CONFIGURATION.md`; `docs/guide/playing/controls.md` | Provider-owned `SpecialStageDebugCapabilities` makes stage-provider support explicit: S2 exposes sprite/plane/alignment/lag diagnostics, S1 exposes direct movement, S3K exposes stage/layout navigation, and unsupported stage actions are not routed into silent hooks. Shared F1/F3/F4/F12 overlay/screenshot actions remain active. |
| SMPS meta commands | `docs/guide/cross-referencing/architecture-overview.md`; `docs/guide/contributing/audio-system.md`; `docs/guide/contributing/architecture.md`; `docs/architecture/research/audio/2026-08-08-s3k-smps-meta-command-reachability.md` | Record the complete loader/native reachability result, including both 173-entry native banks, ROM-asserted `DC-DF` dispatch/aliases, and operand-alignment-only handling for custom streams. |
| Checklist meaning | `S3K_OBJECT_CHECKLIST.md` | Checked means registry coverage, not full ROM parity; dynamic children can be absent. |
| S3K blockers | `docs/status/s3k-known-bugs.md` | Track napalm, Big Arm, and falling intros; mark the splash-child entry resolved while keeping historical trace material. |
| All other rows | This audit | First durable current record until a feature owner updates its status/roadmap. |

No deferred source deletion follows merely from marker text. Future work needs the
cited source-of-truth proof, owner tests, rewind coverage for object state, and
route trace evidence where behavior affects a playable path. Runtime assets stay
ROM-loaded; disassembly is research evidence only.
