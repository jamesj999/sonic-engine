# S1/S2 PLC producer and call-site audit

**Date:** 2026-07-29
**Status:** implemented and validated
**Scope:** the queued `NewPLC`/`AddPLC`/`LoadPLC`/`LoadPLC2` producers whose
runtime trigger already has a concrete OpenGGF owner. This is not an inventory
of synchronous `QuickPLC`, DPLC, or all eagerly decoded art.

## Purpose and method

Task 5 originally referred to a Task 1 producer table, but Task 1 recorded
the native queue model and timing captures rather than such a table. This audit
is that missing source-of-truth table. Each row was derived by pairing the ROM
call site with the Java owner that already implements the same state transition
or threshold. A row is eligible to route only when both sides exist; a ROM call
site alone is not permission to manufacture a new lifecycle owner.

`append` means S1 `AddPLC` or S2 `LoadPLC`. `replace` means S1 `NewPLC` or S2
`LoadPLC2`. The renderer column deliberately describes the existing eager
path, not a substitute for the logical submission: cache hits must still append
or replace the logical queue.

## Task 5 routing table

These are the complete currently represented producer routes, including native
title-screen and level setup before their respective readiness loops.
Producer-coverage tests must parameterize from these tables and assert every
listed PLC ID, operation, and immediate renderer availability. Where the ROM
helper performs two calls, its two submissions are independently asserted in
the stated order.

### Sonic 1

| ROM producer | PLC / operation | Existing OpenGGF owner and trigger | Eager renderer path | Task 5 status |
|---|---:|---|---|---|
| `GM_Title` (`sonic.asm`) title setup | `0` (`Main`) / replace | `GameLoop.initializeTitleScreenMode` through `Sonic1TitleScreenManager.initialize`, after title ROM data is available and before the title presentation begins | `Sonic1TitleScreenDataLoader` | Route once per title-screen initialization; the next `TITLE_SCREEN` lifecycle row services the prepared queue |
| `GM_Credits` (`sonic.asm`) next-demo prequeue | clear; selected level-header primary / append; `1` (`Main2`) / append | `Sonic1CreditsManager.initialize` for credit 0 and `onReturnToText` for each subsequent text entry, after the ROM-equivalent `EndingDemoLoad` selection and before the `CREDITS_TEXT` loop begins | `Sonic1ObjectArtProvider.loadArtForZone` for the selected credits preload target | Route in exact clear/primary/Main2 order on all nine text pages; the first `CREDITS_TEXT` row services/prepares it, so the later `GameLoop.loadEndingDemoZone` level load must not move this submission later. On final index 8, preserve the ROM's `EndDemo_Levels[8]` overread into the following `EndDemo_LampVar` bytes (`0x0101`): it schedules no demo after incrementing to 9, but still queues that selected primary (if nonzero) and `Main2` |
| `Level` setup (`sonic.asm`) | clear; level-header primary / append; `1` (`Main2`) / append | `Sonic1LevelInitProfile.levelLoadSteps`, after the level's ROM header has been decoded and before its requested title card is admitted | `Sonic1.loadLevel` plus `Sonic1ObjectArtProvider.loadArtForZone` | Route in exact clear/primary/Main2 order; omit a zero primary ID as ROM does |
| `DLE_GHZ3_Boss` (`_inc/DynamicLevelEvents.asm`) | `17` / append | `Sonic1GHZEvents.updateAct3Boss`, camera reaches `boss_ghz_x` (`0x2960`) | `Sonic1ObjectArtProvider.loadArtForZone` | Route |
| `DLE_LZ3` | `17` / append | `Sonic1LZEvents.checkBossSpawn`, camera at least `boss_lz_x - 0x140` and above the ROM Y guard | `Sonic1ObjectArtProvider.loadArtForZone` | Route |
| `DLE_MZ3_Boss` | `17` / append | `Sonic1MZEvents.updateAct3Boss`, camera reaches `boss_mz_x - 0x10` (`0x17f0`) after the pre-arena transition | `Sonic1ObjectArtProvider.loadArtForZone` | Route |
| `DLE_SLZ3_Boss` | `17` / append | `Sonic1SLZEvents.updateAct3Boss`, camera reaches `boss_slz_x` (`0x2000`) | `Sonic1ObjectArtProvider.loadArtForZone` | Route |
| `DLE_SYZ3_Boss` | `17` / append | `Sonic1SYZEvents.updateAct3Boss`, camera reaches `boss_syz_x` (`0x2c00`) | `Sonic1ObjectArtProvider.loadArtForZone` | Route |
| `DLE_SBZ2_Blocks` | `30` / append | `Sonic1SBZEvents.updateSBZ2Boss`, camera reaches `boss_sbz2_x - 0x1a0` (`0x1eb0`) and spawns the false floor | `Sonic1ObjectArtProvider.loadArtForZone` | Route |
| `DLE_FZ_Main` | `31` / append | `Sonic1SBZEvents.updateFZMain`, camera reaches `boss_fz_x - 0x308` (`0x2148`) | `Sonic1ObjectArtProvider.loadArtForZone` | Route, with Task 6 replacing the legacy `Sonic1FzPlcTimingQueue` consumer |
| `GotThroughAct` (`_incObj/0D Signpost.asm`) | `16` / replace | `Sonic1SignpostObjectInstance.triggerGotThroughAct` and `Sonic1EggPrisonObjectInstance.triggerGotThroughAct` | `Sonic1ObjectArtProvider.loadArtForZone` | Route at both existing result-entry owners |
| `Card_ChangeArt` (`_incObj/34 Title Cards.asm`) | `2` (explode), then zone animal `21`–`26` / append | `Sonic1FixedTitleCardManager.update`, on the ordinary level frame the level-name element is already at `card_finalX` (the routine-4 element only), armed at the `Level_StartGame` release boundary so a headless load submits it on the same logical frame as a presented one | `Sonic1ObjectArtProvider.loadArtForZone` | Route once, in that order |
| special-stage result setup (`sonic.asm`) | `0` / replace, then `27` / append | `GameLoop.doEnterResultsScreen` through `Sonic1SpecialStageProvider.createResultsScreen` | `Sonic1SpecialStageResultsScreen` self-contained ROM renderer | Route, in this order |

### Sonic 2

| ROM producer | PLC / operation | Existing OpenGGF owner and trigger | Eager renderer path | Task 5 status |
|---|---:|---|---|---|
| `TitleScreen` (`s2.asm`) title setup | `0` (`Std1`) / replace | `GameLoop.initializeTitleScreenMode` through `TitleScreenManager.initialize`, after title ROM data is available and before the title presentation begins | `Sonic2TitleScreenDataLoader` | Route once per title-screen initialization; the next `TITLE_SCREEN` lifecycle row services the prepared queue |
| `Level` setup (`s2.asm`) | clear; zone-art primary / append; `1` (`Std2`) / append; selected player-life `6` (Miles 2P), `7` (Miles 1P), `8` (Tails 2P), or `9` (Tails 1P) / append | `Sonic2LevelInitProfile.levelLoadSteps`, after ROM level-art and player-mode information are available and before the title-card loop | `Sonic2ObjectArtProvider.loadArtForZone` | Route in exact order; apply the ROM player/two-player/graphics flag selection, skip an absent primary, and submit **no** life PLC in 1P when `Player_mode != 2` (the ROM branches directly to `Level_ClrRam`) |
| `loadZoneBlockMaps` after `LoadZoneTiles` (`s2.asm:20069-20110`) | level-header secondary at byte `+4` / append | the `Sonic2LevelInitProfile` initial-presentation completion invoked by visible `PostTitleCardDestination.LEVEL` release and by the production omitted-presentation boundary | `Sonic2ObjectArtProvider.loadArtForZone` already materializes the same ROM cue | Route once after the primary + `Std2` queue has drained; do not drain the secondary synchronously and do not fold the later standard-water/animal overlay calls into this boundary |
| `LevEvents_EHZ2_Routine2` (`s2.asm`) | `41` / append | `Sonic2EHZEvents`, routine 2 at camera X `0x28f0` | `Sonic2ZoneEvents.requestSonic2Plc` → `Sonic2ObjectArtProvider.requestPlc` | Route |
| `LevEvents_MTZ3_Routine3` | `46` / append | `Sonic2MTZEvents`, routine 4 at camera X `0x2a80` | same | Route |
| `LevEvents_WFZ_Routine0` | `62` / append | `Sonic2WFZEvents.secondaryRoutine0BossPlc`, camera X `0x2880` and Y `0x400` | same | Route |
| `LevEvents_WFZ_Routine2` | `63` / append | `Sonic2WFZEvents.secondaryRoutine2ControlLock`, camera Y `0x500` | same | Route |
| `LevEvents_HTZ2_Routine7` | `42` / append | `Sonic2HTZEvents`, boss-arena routine at camera X `0x2edf` | same | Route |
| `LevEvents_OOZ2_Routine2` | `47` / append | `Sonic2OOZEvents`, boss-arena routine at camera X `0x2880` | same | Route |
| `LevEvents_MCZ2_Routine2` | `44` / append | `Sonic2MCZEvents`, boss-arena routine at camera X `0x20f0` | same | Route |
| `LevEvents_CNZ2_Routine2` | `45` / append | `Sonic2CNZEvents`, boss-arena routine at camera X `0x2890` | same | Route |
| `LevEvents_CPZ2_Routine2` | `40` / append | `Sonic2CPZEvents`, boss-arena routine at camera X `0x2a20` | same | Route |
| `LevEvents_DEZ_Routine1` | `48` / append | `Sonic2DEZEvents`, Mecha Sonic transition at camera X `0x140` | same | Route |
| `LevEvents_DEZ_Routine3` | `49` / append | `Sonic2DEZEvents`, boss transition at camera X `0x300` | same | Route |
| `LevEvents_ARZ2_Routine1` | `43` / append | `Sonic2ARZEvents`, boss-arena transition at camera X `0x2810` | same | Route |
| `Load_EndOfAct` (`s2.asm`) | `38` or `66` / replace | `SignpostObjectInstance.spawnResultsScreen` and `EggPrisonObjectInstance.triggerEndOfAct`; choose `66` for Tails-only mode, otherwise `38` | `Sonic2ObjectArtProvider.requestPlc` | Route at both existing end-of-act owners |
| `Obj34_LoadStandardWaterAndAnimalArt` (`s2.asm`) | `2` (`StdWtr`), then zone animal `50`–`59` / append | `TitleCardManager.updateTextExit`, exactly when `zoneNameElement.hasExited()` first becomes true | `Sonic2ObjectArtProvider.requestPlc` | Route once, in that order |
| one-player special-stage result setup (`s2.asm`) | `0` / replace | `GameLoop.doEnterResultsScreen` through `Sonic2SpecialStageProvider.createResultsScreen` | `SpecialStageResultsScreenObjectInstance` self-contained ROM renderer | Route |
| special-stage gameplay handoff (`s2.asm`) | `61` / append | `Sonic2SpecialStageIntro.updateWait2Phase` at the one-shot `specialStageStarted = true` transition; `Sonic2SpecialStageManager.scheduleRecurringMainPass` is the consuming loop boundary | special-stage renderer already owns bomb art | Route exactly once at the semantic gate |

### Sonic 2 boss-defeat producers

`Boss_Defeat` appends the capsule PLC at the killing-hit transition. Its later
`LoadPLC_AnimalExplosion` helper appends the zone-selected animal PLC and then
PLC `65` (explosion). The owner is deliberately the existing game-specific
boss state, rather than a shared boss base: it keeps the ROM's differing
post-defeat handoff frame observable.

| Boss / ROM path | Capsule producer: ID / operation / Java trigger | Animal + explosion producer: IDs / operation / Java trigger | Eager renderer path | Task 5 status |
|---|---|---|---|---|
| EHZ, Obj56 | `64` / append at `Sonic2EHZBossInstance.onDefeatStarted` | `50`, then `65` / append in `updateSubAFlyingOff` tertiary routine `2` when its `0x32` wait expires and it transitions to tertiary routine `4` | `Sonic2ObjectArtProvider.requestPlc` | Route |
| HTZ, Obj52 | `64` / append at `Sonic2HTZBossInstance.onDefeatStarted` | `52`, then `65` / append at the one-shot `defeatFleeStarted` handoff in `updateDefeated` | same | Route |
| ARZ, Obj89 | `64` / append at `Sonic2ARZBossInstance.onDefeatStarted` | `59`, then `65` / append in `updateMainSubA` at countdown `0x18`, the custom defeated ascent-to-flee handoff | same | Route |
| MCZ, Obj57 | `64` / append at `Sonic2MCZBossInstance.onDefeatStarted` | `51`, then `65` / append when `updateSubAHoverDown` reaches countdown `0x18` | same | Route |
| CNZ, Obj51 | `64` / append at `Sonic2CNZBossInstance.onDefeatStarted` | `57`, then `65` / append in `updateDefeatBounce` at countdown `0x18` | same | Route |
| CPZ, Obj5D | `64` / append at `Sonic2CPZBossInstance.onDefeatStarted` | `58`, then `65` / append in `updateMainStopExploding` at the level-music handoff (defeat timer `0x30`) | same | Route |
| MTZ, Obj54 | `64` / append when `applyPendingHitReactionAfterMove` applies the deferred `pendingDefeatReaction` into the ROM `Obj54_Defeated` pass | `52`, then `65` / append on the first `updateSub12Flee` pass that sets `bossDefeatedFlag` | same | Route |
| OOZ, Obj55 | `64` / append at `Sonic2OOZBossInstance.onDefeatStarted` | `55`, then `65` / append on the one-shot defeated-flag/music handoff in `updateMainDefeated` | same | Route |

WFZ and DEZ do not take the normal animal/capsule end-of-act path; their
implemented final-boss sequences are therefore not rows in this table. A
future producer added to either sequence must be audited by its ROM operation,
rather than inheriting the ordinary-boss entries.

## Stable route keys

Every represented Route above has exactly one stable key. The executable
producer guard compares this exact set with its test-case registry, not merely
the row count; amend this table and the registry together.

| Route key | Audited owner row |
|---|---|
| `S1_TITLE` | S1 `GM_Title` |
| `S1_CREDITS` | S1 `GM_Credits` |
| `S1_LEVEL` | S1 level setup |
| `S1_GHZ_EVENT` | S1 `DLE_GHZ3_Boss` |
| `S1_LZ_EVENT` | S1 `DLE_LZ3` |
| `S1_MZ_EVENT` | S1 `DLE_MZ3_Boss` |
| `S1_SLZ_EVENT` | S1 `DLE_SLZ3_Boss` |
| `S1_SYZ_EVENT` | S1 `DLE_SYZ3_Boss` |
| `S1_SBZ_EVENT` | S1 `DLE_SBZ2_Blocks` |
| `S1_FZ_EVENT` | S1 `DLE_FZ_Main` |
| `S1_RESULTS` | S1 `GotThroughAct` |
| `S1_TITLE_CARD` | S1 `Card_ChangeArt` |
| `S1_SPECIAL_RESULTS` | S1 special-stage result setup |
| `S2_TITLE` | S2 title setup |
| `S2_LEVEL` | S2 level setup |
| `S2_LEVEL_SECONDARY` | S2 post-title `loadZoneBlockMaps` |
| `S2_EHZ_EVENT` | S2 EHZ event |
| `S2_MTZ_EVENT` | S2 MTZ event |
| `S2_WFZ_BOSS_EVENT` | S2 WFZ routine 0 |
| `S2_WFZ_TORNADO_EVENT` | S2 WFZ routine 2 |
| `S2_HTZ_EVENT` | S2 HTZ event |
| `S2_OOZ_EVENT` | S2 OOZ event |
| `S2_MCZ_EVENT` | S2 MCZ event |
| `S2_CNZ_EVENT` | S2 CNZ event |
| `S2_CPZ_EVENT` | S2 CPZ event |
| `S2_DEZ_MECHA_EVENT` | S2 DEZ routine 1 |
| `S2_DEZ_ROBOT_EVENT` | S2 DEZ routine 3 |
| `S2_ARZ_EVENT` | S2 ARZ event |
| `S2_RESULTS` | S2 `Load_EndOfAct` |
| `S2_TITLE_CARD` | S2 title-card exit |
| `S2_SPECIAL_RESULTS` | S2 special-stage result setup |
| `S2_SPECIAL_HANDOFF` | S2 special-stage gameplay handoff |
| `S2_EHZ_BOSS` | S2 EHZ ordinary boss defeat |
| `S2_HTZ_BOSS` | S2 HTZ ordinary boss defeat |
| `S2_ARZ_BOSS` | S2 ARZ ordinary boss defeat |
| `S2_MCZ_BOSS` | S2 MCZ ordinary boss defeat |
| `S2_CNZ_BOSS` | S2 CNZ ordinary boss defeat |
| `S2_CPZ_BOSS` | S2 CPZ ordinary boss defeat |
| `S2_MTZ_BOSS` | S2 MTZ ordinary boss defeat |
| `S2_OOZ_BOSS` | S2 OOZ ordinary boss defeat |

## Deliberately excluded ROM producer families

The following calls exist in the disassemblies but are not Task 5 routes. The
current engine either decodes their art at bootstrap or lacks the matching
runtime owner. Adding a service submission to an art provider, level decoder,
or a guessed update phase would produce a false timing event.

| Game | ROM family | Current OpenGGF status and disposition |
|---|---|---|
| S1 | `SignpostArtLoad`, game-over, animal/ending/try-again calls | Art is eagerly available through `Sonic1ObjectArtProvider`, but no Java owner currently mirrors the corresponding ROM producer transition. `QuickPLC` ending/try-again calls are also synchronous and outside this queue. |
| S2 | game-over and two-player result calls | The table covers title, normal level/title-card setup, and the one-player special-stage result. These remaining presentation flows have no matching production submission owner. |
| S2 | `SignpostArtLoad` | The related art is eagerly registered, but no Java owner currently mirrors the ROM's camera/HUD-timer producer condition. Do not place a queue call in the renderer instead. |

## Consequences for implementation and validation

### Constraints retained after the decompression-queue merge

The current S2 session model exposes `PlayerCharacter`, but it does not expose
the retail two-player flag or the player-graphics sign bit used by the
`PlrList_Std1` branch.  Consequently a producer may correctly select the
represented Tails-alone one-player route (`9`), but it cannot honestly submit
the distinct 2P Miles/Tails routes (`6`/`8`) or distinguish every retail
`Player_mode` combination until that session-owned input exists.  Do not infer
those flags from renderer residency.  This is an explicit remaining model
gap, not a trace-recording exception.

The eager renderer and logical queue now share an all-or-nothing publication
boundary. `Sonic2RuntimePlcPublisher` preflights both capacities before either
side publishes, preserves logical submissions on renderer cache hits, and
rejects the whole request when either side cannot accept it. S1 represented
owners commit through `Sonic1PlcService` transactions; S1 has no corresponding
mutable eager-renderer publication. Producer owners retain rejected work and
retry it, so a temporary capacity failure cannot silently lose a native request.

1. The request helpers treat PLC service admission and prepared eager renderer
   registration as one transaction. A renderer cache hit does not suppress the
   logical queue operation.
2. S2 event, end-of-act, presentation, and object owners publish through the
   game-owned boundary rather than placing queue policy in shared object bases.
3. S1 represented owners publish through its corresponding game-owned
   boundary. The FZ producer submits at its native threshold and its consumer
   now observes `Sonic1PlcService.isBusy()` rather than a surrogate countdown.
4. Coverage is exhaustive over all disassembly producers with a represented
   Java owner. A future producer owner must amend this audit and the Task 5
   coverage table before it submits a queue operation. Eager availability alone
   is not such an owner.
