# S3K Kosinski queue caller audit

Date: 2026-07-29

## Scope

This audit covers every `Queue_Kos` and `Queue_Kos_Module` call in
`docs/skdisasm/sonic3k.asm`, the queue processors and reset sites, every gameplay poll
identified by the hardware-timing inventory, and the corresponding production owners in
the Java engine. The disassembly is evidence only; runtime bytes continue to come from
the user-supplied ROM.

The scan found 187 queue call instructions, including tail-call forms and the internal
module-to-direct submission in `Process_Kos_Module_Queue`. Coalescing only immediately
adjacent calls under their nearest local label gives 124 submission clusters. Rolling
those clusters up to the nearest stable routine/object owner gives the 90 owner rows
below. The 187 instructions remain distinct FIFO submissions; neither rollup is a runtime
deduplication.

The inventory is reproducible from the disassembly with:

```bash
rg -n -B20 '(^|\\()Queue_Kos(_Module)?\\)?\\.(l|w)|bsr\\.(s|w)[[:space:]]+Queue_Kos|jmp[[:space:]]+\\(Queue_Kos' \
  docs/skdisasm/sonic3k.asm
```

The complete 124-row cluster ledger is checked in beside this audit as
[`2026-07-29-s3k-kos-queue-submission-clusters.tsv`](2026-07-29-s3k-kos-queue-submission-clusters.tsv).
Its kind column describes the cluster's first call; the instruction-level extraction is
authoritative where a cluster contains both kinds. Repeated calls on one line do not
occur. The earlier hardware-timing audit independently found 24 gameplay polls of
`Kos_modules_left` and two polls of `Kos_decomp_queue_count`.

## Queue contract

`Queue_Kos` submits RAM decompression. `Queue_Kos_Module` submits VRAM art modules and
uses the same direct FIFO for each child module. Gameplay consumers that test
`Kos_modules_left` wait for the global module queue to empty; retained module handles own
payload and identity only. Direct consumers in AIZ and ICZ wait on the global direct
queue. FIFO order matters when direct and module work overlap.

The engine's session-owned `S3kRuntimeArtCoordinator` models both queues and exposes both
global queue predicates and per-submission handles. A ROM poll of `Kos_modules_left`
maps to `modulesLeft()`, and a poll of `Kos_decomp_queue_count` maps to the direct global
pending predicate. Handles remain necessary for payload ownership, claiming, rewind, and
stable replay identity; handle readiness alone is equivalent to a global-empty poll only
where the lifecycle proves that no later work can overlap. A fixed frame delay is not an
equivalent completion predicate: cost varies with compressed input, contention, replay
timing authority, and module child scheduling.

## Submission inventory

The following table is exhaustive at the stable-owner level. `D` and `M` summarize the
kinds owned; the adjacent TSV preserves every distinct cluster edge and first call line.

| Area | Owners (kind, first line) |
|---|---|
| Queue infrastructure and synchronous modes | `Process_Kos_Module_Queue` (D 2740), `Wait_SegaS3K` (D 5528), `SK_Alone_Title_Screen` (D 6427, M 6484), `Obj_SKTitle_SonicFallMain` (M 6747), `Obj_SKTitle_SonicFallFinish` (M 6815), `LoadLevelLoadBlock` (M 9727) |
| Special-stage and form support | `Draw_SSSprite_FlyAway` (M 12610), `Obj_HyperSonic_Stars` (M 34458), `Obj_SuperTailsBirds` (M 35008), `SSEntryRing_Display` (M 128487), `Obj_HPZSSEntryControl` (M 197745) |
| Resize, common UI, and enemy PLC | `AIZ1_Resize_Index` (D 38921, M 38995), `AIZ2_SonicResize4` (D 39112), `AIZ2_KnuxResize4` (D 39223), `LBZ2_Resize_Index` (D 39509), `Map_StarpostStars` (M 61886), `Obj_TitleCardInit` (M 62124), `Obj_LevelResultsInit` (M 62522), `SpecialStage_Results` (M 63063), `LoadEnemyArt` (M 64308) |
| HCZ runtime objects | `HCZWaterWall_Horizontal_QueueArt` (M 64857), `HCZWaterWall_Vertical_QueueArt` (M 65120), `HCZLargeFan_QueueArt` (M 65607) |
| Zone transitions and refreshes | `AIZ1BGE_FireTransition` (D 104672), `HCZ1BGE_Normal` (D/M 105723), `MGZ1BGE_Normal` (D/M 106290), `Obj_CNZTeleporter` (M 108054), `FBZ2_ScreenInit` (M 109318), `ICZ1BGE_Normal` (D/M 110265), `LBZ1BGE_Normal` (D/M 111210), `MHZ2_ScreenEvent_Index` (M 112596), `MHZ2_BackgroundEvent_Index` (D/M 112953), `SOZ1_BackgroundEvent_Index` (D/M 113652), `SOZ2_BackgroundEvent_Index` (D/M 114604), `LRZ1_BackgroundEvent_Index` (D/M 115281), `LRZ2_BackgroundEvent_Index` (M 115833), `SSZ1_ScreenEvent_Index` (D/M 115983), `DEZ1_BackgroundEvent_Index` (D/M 118667), `Ending_ScreenEvent_Index` (D/M 120994) |
| Credits, continue, and ending objects | `PLC_SKCredits` (M 121798), `Obj_Continue_TailsWSonic` (M 123372), `Obj_5D86A` (M 123703), `Obj_5DFEE` (M 124263), `Obj_Ending` (M 124417), `VInt_TableFC` (M 124814), `Obj_5EF68` (M 126043) |
| Knuckles cutscenes | `MHZ1CutsceneButton_LoadKnucklesPeer` (M 130081), `CutsceneKnux_MHZ2_Index` (M 130459), `Obj_SkipIntro` (M 130852), `Obj_LRZ2CutsceneKnuckles` (M 131149), `CutsceneKnux_HPZ` (M 131825), `PLC_KnuxHPZCutsceneShip` (M 132863), `CutsceneKnux_SSZ_Index` (M 133588) |
| AIZ and HCZ bosses/cutscenes | `AIZPlaneIntro_PlaneChildInit` (M 135738), `Obj_RobotnikHeadInit` (M 136067), `Obj_MechaSonicHead` (M 136261), `AIZEndBoss_StartArenaLock` (M 138037), `HCZEndBossGeyser_LoadArt` (M 141553) |
| MGZ through LBZ bosses | `Obj_MGZ2DrillingRobotnik` (M 142399), `MGZ2DrillingRobotnik_Index` (M 142644), `MGZEndBoss_Index` (M 142763), `Obj_MGZEndBossKnux` (M 143007), `Obj_CNZEndBoss` (M 146062), `FBZMiniboss_Index` (M 146802), `FBZ2Subboss_Index` (M 148169), `FBZEndBoss_Index` (M 148949), `LBZFinalBoss1_Index` (M 152145), `LBZEndBoss_Index` (M 153380), `LBZFinalBoss2_Index` (M 154282) |
| MHZ through LRZ bosses | `MHZMiniboss_Index` (M 155659), `Obj_MHZEndBoss` (M 156907), `Obj_SOZMiniboss` (M 157825), `SOZEndBoss_Index` (M 158803), `Obj_LRZMiniboss` (M 160066), `Obj_LRZ3Autoscroll` (M 160911), `RawAni_7917E` (M 161314), `Obj_LRZEndBoss` (M 161573) |
| SSZ through DDZ bosses | `Obj_SSZGHZBoss` (M 162600), `Obj_SSZMTZBoss` (M 163041), `SSZEndBoss_Index` (M 164233), `PLC_KnuxFinalBossCrane` (M 166202), `Obj_DEZMiniboss` (M 167686), `Obj_DEZEndBoss` (M 169561), `DEZ3_Boss_Index` (M 170946), `ChildObjDat_81330` (M 173282), `DDZEndBoss_Index` (M 173587) |
| Late object-file owners | `LBZ1Robotnik_Index` (M 192194), `Obj_LBZMinibossBoxKnux` (M 192665), `Obj_LRZRockCrusher` (M 197032), `Obj_EggRobo` (M 198480) |

The owner table is the lifecycle map. The extraction command above is the instruction
level check: it includes multiple queue calls inside a single cluster (for example the
two direct plus one module submissions in seamless transitions), tail calls, and the
module processor's child submission. Review of all 187 matches confirmed no call lies
outside one of the owners listed here.

## Engine disposition

### Queue-correct production owners

The engine already submits and retains queue identities for AIZ direct/module event work,
the ICZ act transition, title cards, results, StarPost bonus art, the CNZ teleporter, AIZ
plane/boss work, HCZ water walls/fan/geyser, and the implemented AIZ/HCZ/MGZ/CNZ/ICZ enemy
art profiles. These owners use production ROM descriptors and retain their submission
handles. Predicate parity is a separate question: owners that currently use handle
readiness where the ROM polls global empty require an overlap proof or conversion to the
global predicate. They are not declared queue-predicate-correct merely because submission
identity is correct.

### Confirmed mismatches

| Engine owner | ROM behavior | Current behavior | Required correction |
|---|---|---|---|
| HCZ1-to-HCZ2 | Two direct jobs followed by one module job; transition polls global module-queue empty (`sonic3k.asm:105723-105754`). | Only the module job is submitted and the engine polls its handle. | Submit the two direct jobs first, retain all handles for rewind/lifetime, and gate on global module-queue empty. |
| MGZ1-to-MGZ2 | Two direct jobs followed by one module job; transition polls global module-queue empty (`sonic3k.asm:106290-106314`). | No queue submission; a 26-frame timer approximates decompression. | Replace the timer with exact direct/module descriptors and gate on global module-queue empty. |
| LBZ1-to-LBZ2 | Two direct jobs followed by one module job; transition polls global module-queue empty (`sonic3k.asm:111210-111235`). | No queue submission; a 55-frame timer approximates decompression. | Replace the timer with exact direct/module descriptors and gate on global module-queue empty. |

These are synchronization-affecting gaps: completion changes the frame on which act
state, layouts, managers, camera/event state, and subsequent object processing advance.

### Deferred by ownership, not assumed correct

The remaining disassembly owners fall into three classes:

1. An engine owner is not implemented yet (many later bosses, cutscenes, ending, and
   credits). Queue work belongs in that owner when it is ported.
2. The engine currently preloads an asset through level/bootstrap infrastructure and has
   no ROM lifecycle consumer. This may render correctly but is not queue-timing parity;
   it must be revisited when the owning lifecycle is implemented or traced.
3. The owner exists but has not yet been compared instruction-for-instruction. Later-zone
   transitions, post-boss refreshes, and implemented bosses are the highest-risk members
   because the ROM has an explicit completion gate.

No deferred entry is treated as evidence that eager loading is semantically equivalent.
The implementation pass prioritizes confirmed active owners with explicit transition
gates; future object/zone work should consult this inventory rather than adding a global
queue call or a game/zone carve-out.

## Reset and rewind findings

Queue state is session-owned and cleared only by the runtime-art coordinator's session
reset lifecycle. Seamless level loads do not indiscriminately clear it; ICZ deliberately
transfers prepared jobs across the act handoff. Event owners capture scalar submission
ordinals and submitted state in rewind snapshots. Queue facades and handles are transient
and rebound through `HardwareTimingService.pendingHandle(kind, ordinal)` after the timing
ledger restores. Restoration must not resubmit a duplicate job. No static queue state or
singleton access is required.
