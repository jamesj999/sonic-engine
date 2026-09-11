# S3K boss init-dispatch survey

**Date:** 2026-08-21 · **Scope:** `src/main/java/com/openggf/game/sonic3k/objects/bosses`
· **Status:** survey only, nothing fixed.

Companion to the badnik init-dispatch survey, which covered badniks only. The question is the
same: does the class model the ROM's routine-zero dispatch — the frame in which an object sets
up and returns without doing its first routine's work?

## Sizing

48 files; 4 are helpers with no `update()` (`CnzEndBossRewindLinks`, `HczEndBossRewindLinks`,
`S3kBossFlickerMove`, `S3kSharedBossCameraGate`), leaving **44 object classes**. Five name an
init routine directly. **That 5-of-44 is a fact about a grep, not a defect count** — it is one
of five idioms in use, and the other four are invisible to it.

## Reference shape

`AizEndBossInstance`: a declared `ROUTINE_INIT`, a `switch` on the routine index, and the
selected case cannot fall into the next, so exactly one routine runs per frame. Work after the
switch is not itself a fault — the ROM's own dispatchers usually have a common tail
(`Obj_AIZEndBoss` runs `SolidObjectFull` + `Draw_Sprite` after `jsr`).

## Verdicts

### Models the dispatch — 11

| class | idiom |
|---|---|
| `HczEndBossInstance` | `ROUTINE_INIT -> updateInit()` |
| `HczEndBossRobotnikShip` | `ROUTINE_INIT -> updateInit()` |
| `HczEndBossTurbine` | `ROUTINE_INIT` |
| `HczEndBossWaterColumn` | `ROUTINE_INIT` |
| `IczEndBossInstance` | `ROUTINE_INIT` case reserves child slots, then `enterDescend()` |
| `CnzEndBossInstance` | enum `INIT -> initializeNativeRoutineZero()` |
| `CnzEndBossMagnetChild` | `dropJustStarted` latch |
| `LbzFinalBoss1Instance` | `nativeInitPassPending` guard that returns |
| `HczEndBossBladeWaterChute` | `initialized` guard that returns |
| `HczEndBossBladeImpactExplosion` | `initialized` guard that returns |
| `HczEndBossBubbleParticle` | `initialized` guard that returns |

`CnzEndBossMagnetChild` is the clearest statement of the contract anywhere in the tree, and it
is a comment rather than a name:

```java
// loc_6E87E installs the falling routine and returns; MoveSprite
// starts when routine 4 is dispatched on the next object pass.
```

### Does NOT model the dispatch — 1

**`MhzEndBossInstance`.** Verified against the ROM, not inferred. `Obj_MHZEndBoss`
(`sonic3k.asm:156893-156919`) is the object's first-dispatch entry: it runs
`Check_CameraInRange`, locks the arena, `SetUp_ObjAttributes`, queues art, loads the PLC and
palette, creates four sets of children, installs the dispatcher with
`move.l #loc_75FD4,(a0)`, **explicitly undoes `SetUp_ObjAttributes`'s `addq.b #2` with
`clr.b routine(a0)`**, and returns through `jmp (CreateChild6_Simple).l` — a complete frame of
setup with no routine handler run. `loc_75FD4` dispatches routine 0 from the *next* frame.

The engine's `update()` runs `applyInitialArenaSetup()` and `spawnInitialChildrenOnce()` and
then falls straight into the routine dispatch, whose routine 0 is
`ROUTINE_WAIT_FOR_CHILD_SIGNAL` — the ROM's routine 0, correctly numbered. So the setup and
routine 0 execute in the same frame where the ROM separates them. The fold is on the
camera-in-range frame, so it does not depend on when the object was constructed.

Its dispatch is an `else if` chain, which is a fifth idiom but not a fault: exactly one branch
runs per frame.

### Routine machine present, state 0 is not an init — unclassified, 4

Each has a dispatch whose zero state is a wait or a gate rather than a setup, which is the
shape a fold leaves behind — but I did not read their ROM routine tables, and **"no init state
in the engine" is not evidence of a missing frame** until the ROM is shown to have one.

`HczEndBossBlade` (`ROUTINE_ATTACHED = 0`), `HczEndBossGeyserCutscene` (`PHASE_SHAKE = 0`),
`LbzEndBossInstance` (`ROUTINE_GATE = 0`), `CnzEndBossRobotnikShipChild` (switch with no
numbered zero constant).

### No routine machine — 28

The remainder: parent-driven followers that read the parent's routine and hold none of their
own (`CnzEndBossArmChild`, the `Mhz*Child` family, `Hcz*` spray/surface/housing/head), one-shot
effects, egg-capsule subclasses that inherit their machine from
`AbstractS3kUprightEggCapsuleInstance`, and controllers (`MhzEndBossPaletteFadeController`,
`HczEndBossGradualMaxXExtender`, `CnzEndBossBoundaryController`). Setup in their constructors
is a correct fold: they have no routine whose work could have been displaced.

## Answers to the two questions asked

**Does the family extend to bosses?** Yes, and narrowly: **`MhzEndBossInstance`** is the one
verified member. The fix belongs with the badnik lane's family, not separately.

**Are the badnik survey's two idioms a badnik convention?** No — the inconsistency is
engine-wide and worse among bosses. Bosses use five: a declared `ROUTINE_INIT` constant, an
enum `INIT` case with a method named for the ROM's routine zero, an `initialized`/`...Pending`
guard that returns, a `justStarted` boolean latch, and a plain `else if` chain. Only the first
is greppable, which is why the sizing grep found 5 of 44 while 11 classes actually model the
dispatch.

**Recommendation, not taken here:** whatever name the badnik lane settles on should be applied
across both families, because the cost of this survey was almost entirely in discovering that
four of the five idioms are invisible to a search for the fifth.
