# Documentation Gap Audit — 2026-05-30

**Scope:** 635 non-merge commits on `develop` since 2026-05-02 (the "last ~4 weeks" window).
**Method:** A multi-agent workflow triaged every commit (subject + body + trailers + changed files) for changes that *warranted* a doc entry, then per-target synthesis agents checked each candidate against the **current** doc content, dropped anything already covered, and deduped multiple commits into single logical entries.
**Why gaps exist:** the commit-trailer gate only enforces *consistency* (if a doc file is staged, the trailer must say `updated`). It never checks whether a code change *should* have updated a doc — so commits sailed through declaring `n/a` while making changelog-worthy changes.

This is a **report only**. No docs were modified. Proposed text is ready to paste in each doc's existing style.

## Summary

| Target | Genuine gaps | Notes |
|--------|-------------:|-------|
| `CHANGELOG.md` | **40** | Largest backlog. Major features (level editor, rewind framework, full S3K ICZ object set + bosses, S3K badniks) plus dozens of S3K/S2 gameplay+audio fixes never logged. **Also a structural bug — see below.** |
| `CLAUDE.md` + `AGENTS.md` | **6** | Editor package/mode, ENEMY touch-poll contract, level-mutation routing rule, level CoW epoch (+2 low-confidence framework extensions). |
| `CONFIGURATION.md` | **1** | Level-editor hardcoded key/mouse bindings undocumented. |
| `docs/status/known-discrepancies.md` | **1** (low) | S2 music offsets resolved from a hardcoded REV01 table vs ROM-driven playlist tables. Internal-only; include-or-decline judgment call. |
| `docs/S3K_KNOWN_DISCREPANCIES.md` | 0 | All S3K changes are bugfixes-toward-parity, cross-game features, or already-documented divergences. |
| `docs/guide/` | 0 | The one user-facing change (display color profile) is already documented. |
| `.claude/skills/` + `.agents/skills/` | 0 | All work fits existing skills; no new reusable technique warranted a skill. |

---

## ⚠️ Structural finding: CHANGELOG has a stray `## Unreleased` section

This is the confusion you flagged. Current heading structure:

- `# Changelog` (line 1)
- `## Unreleased` (lines 5–96) — **stray**, created by a confused agent. Holds ~20 recent entries.
- `### v0.6.prerelease (Current development snapshot)` (line 97+) — the real release section, but mis-leveled as an `###` (h3).

**Recommended fix (separate from the gap backlog):** fold the `## Unreleased` bullets into the v0.6 section and normalize that heading to `## v0.6.prerelease`. The ~40 missing CHANGELOG entries below should also land in the v0.6 section, not a revived `## Unreleased`.

---

## 1. `CHANGELOG.md` — 40 missing entries

Deduped from 98 candidates; ~7 were already covered (S3K foreground-mask water alignment, S3K SMPS pitch/modulation/spindash/1-up, S2 Octus collision) and excluded. The broad "S3K route parity" summary at lines 99–112 gestures at AIZ/CNZ/MGZ generically but never names these specific features/fixes, so they remain genuine gaps.

### High-confidence — major features

- **Added an in-game level editor MVP.** Toggle into an edit mode mid-play, paint chunks with the mouse, undo/redo strokes via `Block.saveState()/restoreState()`, and persist edits through the editor save envelope. Editor enter/exit uses teardown+rebuild while `WorldSession` survives, re-applying `MutableLevel` edits on resume. *(`865d3111`)*

- **Added the deterministic rewind/playback framework.** New snapshot registry (`RewindSnapshottable`/`CompositeSnapshot`/`RewindRegistry`), in-memory keyframe store, segment cache (O(1) backward step), and `RewindController`/`PlaybackController`, with per-subsystem snapshot adapters (level, object manager, camera, game state, RNG, timers, fade, parallax, water, palette/zone/animated-tile/render registries, level-event managers, RingManager). Generic per-object/sprite field capture, optimized ring and level snapshotting, and trace-mode rewind playback wiring were layered on top, including follow-history buffers and `SidekickCpuController` state so the CPU sidekick resumes identical behavior after seek/replay. *(`3eb3fa9e`, `cf48a9a`, `dea2884`, `7f936b5`)*

- **Added config-gated live rewind.** Hold-to-rewind gameplay playback with an on-screen HUD overlay and dedicated input handler/stepper, gated behind a new configuration flag. The rewind HUD counter resets to 0 at level/act/zone boundaries, and a `stepBackward` crash when the earliest keyframe fell off the keyframe-interval grid after a boundary reset was fixed. *(`1b1c99e3`, `305c1e22`)*

- **Added audio rewind runtime delivery.** A deterministic audio runtime (PCM/FIFO history rings, audio command timeline, and chip/SMPS snapshots) so sound replays correctly during gameplay rewind. *(`13bd082b`)*

- **Rewind: slow-motion (sub-1.0) step rates and speed-matched reverse audio.** Tape-coast rewind supports sub-1.0 step rates for slow-motion rewind (`LIVE_REWIND_TAPE_COAST_MIN_STEPS` floor) and resamples reverse audio playback to match the current rewind speed. *(`321677b4`)*

- **Rewind audio: configurable PCM history cap with larger defaults.** The PCM history cap is now user-configurable by time or size via `REWIND_AUDIO_HISTORY_LIMIT_TYPE` / `REWIND_AUDIO_HISTORY_SECONDS` / `REWIND_AUDIO_HISTORY_SIZE_MB`, and the default limits were raised from 10 s / 2 MB to 60 s / 10 MB. *(`7dfaa8bd`, `e56f423d`)*

### High-confidence — S3K content

- **Implemented the S3K Ice Cap Zone object set.** ICZ ice block (top-solid), ice cube, snow pile (zone variants/art), tension platform, breakable wall, Freezer, harmful ice hazard, crushing column (ROM-sized trigger footprint), stalagtite, and ice spikes, registering the corresponding S3KL object ids. *(`520af223`, `c9e37d2a`, `d553aa0c`, `55d74b71`, `7dec02cd`, `d5136898`, `e5472aa1`, `1ed889dd`)*

- **Implemented the S3K ICZ path-follow platform and swinging platform.** The path-follow platform at its terminal right-wall stop spawns the revealed spring, displaces Sonic off the platform, and deletes the block after the route completes; ridden moving platforms use the ROM `Fast_V_scroll_flag` fast vertical camera cap. The ICZ swinging platform (object 0xB4) has ROM-accurate swing motion, solid collision, and palette-correct rendering. *(`cd57405`, `b72b135f`, `fffad91`)*

- **Implemented the S3K Ice Cap Zone minibosses/bosses.** ICZ1 miniboss (ROM-backed art, post-boss palette cleanup, ICZ1→ICZ2 transition gated on `Apparent_act`) and the ICZ2 end boss (egg capsule, snow-pile interaction) on a shared S3K boss camera-gate. *(`7133e5ec`, `4d63ad53`)*

- **S3K ICZ opening-sequence and background parity.** ICZ scroll handler, opening mountain palette setup, snowboard intro event shell, ROM-gated palette cycling (holds line 4 until the indoor flag is active), animated BG tile uploads, and indoor/outdoor palette event writes. Post-snowboard wall-crash handoff with falling big snow pile, jump-escape collision, lock-on background snow rendering, sprite-priority masking, and segment-column shatter debris. Fixed the snowboard intro title-card handoff and board-launch height (Sonic pinned to the ROM-computed terrain-arc point before board-bounce velocities). *(`dec11b6`, `270bf820`, `fd2dea02`, `2d465239`, `0a190c5`, `c7fef3f9`)*

- **Implemented S3K badniks: Penguinator and StarPointer.** Both with ROM-accurate behavior/art and registered object ids. *(`65df7378`, `bc015dbd`)*

- **Completed the S3K CNZ Act 2 first-Knuckles cutscene.** Pre-seeded flood water level, button screen-shake, end-of-cutscene palette restore, and an invisible blocking wall holding Sonic during the scene; fixed the button-press chain, the water recede (so `Obj_CNZWaterLevelCorkFloor` observes the real CorkFloor child before setting the recede target), and aligned CNZ Act 2 palette cycling and BG scroll with the ROM. Added CNZ actors (CutsceneKnuckles CNZ2 A/B, Batbot/Sparkle badnik, water-level/cutscene button objects, `CnzLightsFlashChild`). *(`76766c85`, `bbd00daa`, `fd63a67f`, `0092da49`, `bb2777a5`)*

- **S3K CNZ miniboss and traversal-object parity.** The CNZ miniboss stays dormant until the arena trigger (`Camera_X >= $31E0`) and 2-second `Obj_Wait`, matching `Obj_CNZMiniboss`; its looping BG band is clamped to the ROM's 256px height. Restored the miniboss act-transition flow (scroll control, top/spark behavior, signpost handoff) and repaired CNZ traversal objects (barber pole crossing handoff, cannon, scripted-velocity animation, debug-mode latch gating, lightbulb, trap-door open-hold, sparkle phases, signpost/results lifetime). *(`47ccc643`, `a513e181`, `e788d1c5`, `46d8c796`, `508c0857`, `683b9c15`, `ccfe3377`)*

- **S3K AIZ route fixes.** Fire-curtain effect survives the seamless AIZ1→AIZ2 reload while clearing stale cache on AIZ1 start / AIZ exit; post-bombing AIZ2 forest-loop wrap lands inside the forest mask and AIZ2 tree objects persist until their ROM delete predicates. Fixed the collapsing fire-log bridge top landing boundary/top-solid gating, AIZ end-boss active-collision timing, AIZ object placement-window rewind after the ship loop, sidekick boundaries after the battleship camera-bounds wrap, and ship-bomb touch response. The AIZ2 battleship auto-scroll now runs in a pre-physics phase with a temporary camera scroll-lock freeze (ROM `SpecialEvents`-before-`Process_Sprites` ordering), and the AIZ2 resize state machine runs before the screen-event handoff. *(`eac8ba47`, `95589458`, `441433c`, `f0e18f0`, `680748d`, `d2f8197`, `172e27b`, `88a4115`, `f4de73f`, `bceff204`, `d376750a`, `11dda93`, `50a2c2b44`)*

- **S3K AIZ physics parity.** Ground-wall push only sets `Status_Push` when the player faces into the contacted wall (S3K), water-exit y-velocity doubling is skipped on fast upward exits (S2/S3K), and the CPU sidekick follow/push logic was reworked to match ROM ordering — gated by new `PhysicsFeatureSet` flags, not zone carve-outs. *(`bc5cfa2b`)*

- **Fixed the S3K spindash release sound effect** so the release plays `sfx_Dash` instead of reusing the spindash charge SFX. *(`bdbb004`)*

- **Fixed music being delayed at level start after a non-gameplay window** (e.g. title → level select → level) by clamping the gameplay audio frame to forward-only progression so backlogged audio commands drain immediately. *(`a9540d92`)*

### Medium / low confidence

- **Newly added default config keys are backfilled into an existing `config.json` on load.** *(`d263e2dd`)*
- **Fixed the S3K end signpost so it persists into Act 2 across the seamless act reload** (offset by the transition world delta) for CNZ/HCZ/MGZ. *(`d58917a2`)*
- **Fixed S3K ICZ frozen-block break damage** — shieldless freeze release spends rings via the hurt/death path; shielded damage only strips the shield. Also fixed stale object grounding in ICZ2, kept the AIZ hollow tree as live support, gated ICZ miniboss touch regions until the live routine starts, and fixed the ICZ2 CorkFloor roll break. *(`1af5e73e`, `a114fc90`, `44eb119d`)*
- **Fixed the S3K HCZ2 end-boss defeat handoff lifetime** and the S3K seamless results-screen transition gate. *(`445307c5`, `c54180dd`)*
- **S3K MGZ route fixes** — MGZ2 end-boss parity (drilling Robotnik art/PLC + events), swinging-platform despawn, object trace parity (dash trigger, swinging platform, monitor, spring, Bubbles badnik), air-roll/sidekick air-collision physics parity (new `PhysicsFeatureSet` flag), and MGZ2 rescue-Tails cleanup. *(`e9e212f`, `8ebe0dbc`, `c93d7c1`, `a0dce4c`, `3898362`)*
- **S2 sidekick death now uses the deferred-despawn flow (`Obj02_Dead`).** Gated by `PhysicsFeatureSet.sidekickDeathUsesDeferredDespawn`. *(`307562728`)*
- **Touch-response framework: ENEMY-category callbacks now poll continuously every frame** (ROM `Touch_Loop`) instead of firing only on first overlap; SPECIAL/monitor contacts remain edge-triggered. *(`e282d7ded`)*
- **Sidekick CPU control tracks the delayed jump-press bit separately from held buttons** (`getJumpPressHistory`). *(`e234294727`)*
- **Improved Sonic 2 Sky Chase Zone parity** (SCZ object placement, Turtloid projectile, Tornado ride input timing, object hurt/platform landing). *(`51c220e`)*
- **Fixed Sonic 2 OOZ oil-surface landing to match ROM `PlatformObject_ChkYRange`** (per-player submersion state, ROM-accurate landing window/snap/inertia). *(`b1c21030`)*
- **Restored the S2/S3K water enter/exit splash** via the fixed `Sonic_Dust` object (new slot-free splash mode). *(`1685d9f8`)*
- **Switching display color profiles now updates on-screen colors live** (reloads active level palettes via `Engine.refreshDisplayPalettes` / `LevelManager.reloadLevelPalettes`). *(`251d7de5`)*
- **Fixed S3K AutoSpin tunnel landings** to preserve the `spin_dash_flag` mirror (S2 pinball landings still clear the pinball-mode mirror). New `PhysicsFeatureSet` flag. *(`e0e51589`)*
- **Gated embedded monitor content (icon) timing to match ROM** across S2 and S3K monitors. *(`2890f18`)*
- **Spindash release no longer resets the camera position history** (only the horizontal scroll-frame offset), reproducing the ROM's old-position camera jerk (S2/S3K). *(`5a296cb7`)*
- **Keep moving CNZ hex bumpers alive based on range bounds** rather than unloading prematurely. *(`a25af3c`)*
- **Fixed CNZ cylinder traversal so the CPU sidekick is recaptured correctly.** *(`3fd4f63`)*
- **Fixed players getting stuck on the master title screen** after returning from a trace/gameplay session (clears the stale runtime `FadeManager` reference). *(`6ae1df1`)*
- **Fixed an object-slot/memory leak** where air-unseat latches for permanently destroyed spawnless dynamic objects were never evicted. *(`491ad09`)*
- **Object slot inventory now resets together with placement state** for deterministic spawn windowing. *(`66aa7285`)*
- **Aligned object solid-contact parity hooks** across `ObjectManager` and `SolidObjectProvider`/`SolidObjectListener`. *(`af15afa`)*
- **Performance: menu and disclaimer text now mega-batch into a single GL text draw per frame** (master title, trace picker, simple data-select, legal disclaimer). *(`1856f87`, `89d94df`)*
- **Completed the runtime session migration.** Retired the `GameRuntime` and `RuntimeManager` façades; gameplay-state ownership flows through `EngineServices`, `SessionManager`, and `GameplayModeContext`. *(`7e5c2dbb`)*

---

## 2. `CLAUDE.md` + `AGENTS.md` — 6 missing entries

Dropped as already documented: object-physics adapter seams / `com.openggf.game.profiles.*` (`f1ef9755`, `69c5cd5c`, doc-aligned by `0a10f909`); S3K badnik reparent onto `AbstractBadnikInstance` (`9a86396f0`, already-documented base class).

1. **Level editor package and `GameMode.EDITOR` (`com.openggf.editor`)** — *(`865d3111`, medium)*
   New `com.openggf.editor` package (`LevelEditorController`, `EditorInputHandler`, `EditorMouseTransform`, `EditorHistory` + `commands.*`, `persistence.EditorSaveManager`/`EditorSaveEnvelope`/`EditorSavePayload`, `render.EditorToolbarRenderer`) and a `GameMode.EDITOR` integrated into `Engine` and `SessionManager`. Currently undocumented at the package level (docs only cover editor enter/exit at the session-ownership layer).

2. **ENEMY touch responses poll every frame** — *(`e282d7ded`, medium)*
   `ObjectManager.TouchResponses` ENEMY callbacks poll continuously (ROM `Touch_Loop`) instead of edge-triggering; SPECIAL/monitor contacts stay edge-triggered. New badnik/damaging-object code should not add consumed-once state.

3. **Level-mutation routing rule** — *(`7745c703`, medium)*
   Gameplay-path tile edits (under `game/sonic1|2|3k`, `level/objects`) must route through `ZoneLayoutMutationPipeline` / a `LevelMutationSurface`, never direct `getMap().setValue`. Enforced by CI test `TestNoDirectMapMutationsInGameplay`; editor commands and initial layout decoders (`Sonic3kLevel`) exempt.

4. **Level snapshot copy-on-write epoch** — *(`9f827b08`, medium)*
   `AbstractLevel.snapshotEpoch` + `cowEnsureWritable` clone `Block.chunkDescs`/`Chunk.patternDescs`/`Map.data` on first write per epoch so rewind snapshots stay isolated. Integrated via `DirectLevelMutationSurface.setBlockInMap`.

5. **Camera honors ROM `Fast_V_scroll_flag`** — *(`cd57405`, low)*
   Vertical camera cap is raised when the player rides a platform that sets the ROM flag (ICZ path-follow platform). ROM-driven, not a zone carve-out.

6. **`ZoneRuntimeState.requiresFullWidthBgTilemap()` default method** — *(`f0efb250`, low)*
   `LevelTilemapManager` consumes a new generic `ZoneRuntimeState` default method instead of probing `GameStateManager` (HTZ earthquake overlay migration into `SpecialRenderEffectRegistry`).

---

## 3. `CONFIGURATION.md` — 1 missing entry

**Level Editor controls (hardcoded)** — *(`865d3111`, medium)*
The doc covers `EDITOR_ENABLED` and the `Shift+Tab` entry, but none of the editor's in-mode controls. Proposed addition (new subsection near `EDITOR_ENABLED`):

> ### Level Editor (experimental)
>
> The in-engine level editor (see `EDITOR_ENABLED`) uses **hardcoded** key/mouse bindings — not configurable in `config.json`. While playing with `EDITOR_ENABLED` true, press `Shift+Tab` to toggle gameplay (playtest) ↔ editor.
>
> | Input | Action |
> |-------|--------|
> | `Shift+Tab` | Toggle editor / playtest mode |
> | Arrows | Move world cursor / nudge selection |
> | `Tab` | Cycle focused region |
> | `Space` | Apply primary action (place selected block) |
> | `E` | Eyedrop block under cursor |
> | `L` | Toggle active layer (FG / BG) |
> | `Enter` / `Escape` | Descend / ascend the hierarchy |
> | `Ctrl+Z` / `Ctrl+Y` / `Ctrl+S` | Undo / Redo / Save |
> | Left mouse (drag) | Paint selected block (one undoable stroke) |
> | Right mouse | Eyedrop hovered tile |
>
> Bindings live in `EditorInputHandler` and are not affected by the Key Bindings entries above.

Verified against `EditorInputHandler.java` and the `Shift+Tab` gate in `GameLoop.java:501-512`.

---

## 4. `docs/status/known-discrepancies.md` — 1 missing entry (low confidence, judgment call)

**S2 Music Offsets Resolved from Hardcoded REV01 Table** — *(`ecd1161a`, low)*
`Sonic2SmpsLoader.findMusicOffset` resolves song offsets from a hardcoded `Sonic2Music`→REV01-offset map instead of the ROM's `zMasterPlaylist`/`MusicPoint` tables (which live inside the Saxman-compressed Z80 driver blob and don't agree with the engine's intentionally-shifted song IDs). **No audible difference** — same SMPS data, different lookup source. The trailer said `Known-Discrepancies: n/a`.

**Include-or-decline:** the existing "Pattern ID Ranges" entry (identical output, only internal storage differs) is the precedent for including internal-only divergences. If this doc is meant only for *observable* divergences, decline and leave the `n/a` trailer as-is. Full proposed entry (Location / Original / Ours / Rationale / Removal Condition) is in the workflow output if you want it.

---

## 5–7. No gaps

- **`docs/S3K_KNOWN_DISCREPANCIES.md`** — every S3K change in the window is a bugfix toward parity, a cross-game feature, or already-documented (AIZ2 battleship wrap; CNZ1 Tails carry). The two commits that touched this doc net out to the already-current AIZ2 entry.
- **`docs/guide/`** — the only user-facing change (display color profile) is already documented in `docs/guide/playing/configuration.md` (the `c3b64d93d` commit updated it).
- **`.claude/skills/` + `.agents/skills/`** — all work fits existing skills (`s3k-implement-object/boss`, `s3k-zone-events/bring-up`, `s3k-parallax`, etc.). No new reusable technique warranted a skill.

---

## Suggested next steps

1. **Fix the CHANGELOG structure first** (fold `## Unreleased` into `## v0.6.prerelease`, normalize heading level).
2. **Backfill the 40 CHANGELOG entries** into the v0.6 section — high-confidence feature group first.
3. **Add the 6 architecture notes** to `CLAUDE.md`/`AGENTS.md` and the editor-controls table to `CONFIGURATION.md`.
4. **Decide** on the single `KNOWN_DISCREPANCIES.md` candidate (include vs. decline).
5. **Close the gate gap going forward:** the trailer policy can't catch a wrong `n/a`. Consider a periodic re-run of this audit workflow (the script is saved and re-runnable) and/or a CI heuristic that flags `feat`/`fix` commits declaring `Changelog: n/a` for human review.

*Generated by the `doc-gap-audit` workflow (22 agents). Scratch input under `doc-audit/` can be deleted.*
