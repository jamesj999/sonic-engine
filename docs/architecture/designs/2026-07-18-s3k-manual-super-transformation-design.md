# S3K Manual Super Transformation Design

## Goal

Match Sonic 3 & Knuckles' ROM behavior for Super/Hyper activation: transformation occurs from a second jump-button press during the airborne ability window, with character-specific emerald and shield rules, while Sonic 2 retains its automatic jump trigger. The resulting state must round-trip through gameplay rewind without replaying one-shot effects.

## Reference Behavior

The authoritative paths are `Sonic_ShieldMoves` / `Sonic_CheckTransform`, `Tails_Test_For_Flight`, and `Knux_Test_For_Glide` in `docs/skdisasm/sonic3k.asm`.

- Sonic tries transformation on the second A/B/C press after elemental shield abilities have been ruled out. Fire, Lightning, and Bubble Shields take priority; a basic S2-style shield does not block transformation. Invincibility suppresses the attempt. Seven Super Emeralds permit Hyper transformation; seven Chaos Emeralds permit Super transformation only while the ROM-equivalent emerald-conversion flag is clear.
- Tails transforms only when he is the main/solo playable character, has all seven Super Emeralds, and has at least 50 rings. Otherwise the press continues into the existing flight decision; CPU Tails does not transform and retains its separate idle-timer flight gate.
- Knuckles transforms with all seven Chaos Emeralds and an unset emerald-conversion flag (Super), or all seven Super Emeralds (Hyper), and at least 50 rings. Otherwise the press starts gliding.
- Tails and Knuckles do not run through Sonic's elemental-shield dispatch, so an elemental shield does not block their transformation.

All three routines require a fresh A/B/C press, `double_jump_flag == 0`, and `y_vel` at or beyond the active jump-height threshold: `y_vel >= -$400` normally or `y_vel >= -$200` underwater. Transformation also requires the active gameplay/HUD timer (`Update_HUD_timer != 0`). Sonic alone rejects transformation while ordinary invincibility is active; the Tails and Knuckles routines contain no corresponding invincibility rejection.

## Emerald Conversion Ownership

The ROM's `Emeralds_converted_flag` cannot be inferred from possession of one or more Super Emeralds: conversion begins in Hidden Palace before the player necessarily earns a Super Emerald. `GameStateManager` will therefore own an explicit durable `emeraldsConverted` value alongside Chaos and Super Emerald progress.

The S3K progression transition that converts the seven Chaos Emeralds will set this value; character controllers only read it. Save import/export and `GameStateManager`'s existing rewind snapshot will carry it. For compatibility with existing saves that have Super Emerald entries but no explicit legacy field, import treats any recorded Super Emerald as converted. New saves persist the explicit field so a converted-zero-Super-Emerald state remains representable. Reset/new-game paths clear it. No controller-local conversion latch is allowed.

## Runtime Design

`SuperStateController` will retain Sonic 2's existing automatic airborne activation as the default. It will expose narrow protected policy hooks for automatic-trigger participation and game-specific eligibility. `Sonic3kSuperStateController` will disable the automatic trigger and own S3K character/emerald eligibility. Shared movement code will not branch on game names or zones. The common safety gates remain death, hurt, debug mode, object control, minimum rings, and active HUD timer; S3K adds character-specific emerald, shield, participation, and invincibility gates.

`PlayableSpriteMovement` will attempt the S3K controller's explicit air-ability activation before each character's fallback ability:

1. Sonic: only after determining that no elemental shield is active; a basic shield remains eligible.
2. Tails: before flight, only when `SpriteManager.getMainPlayable()` resolves to that Tails instance. This models `Player_mode == Tails alone` and avoids treating the project's configurable CPU/multi-sidekick flags as the ROM player-mode value.
3. Knuckles: before glide.

The attempt is made only in the ROM air-ability window: a fresh jump edge, `doubleJumpFlag == 0`, and signed `ySpeed` at or beyond the active normal (`-0x400`) or underwater (`-0x200`) threshold. Earlier ascent remains jump-height handling. If eligibility fails, existing shield, flight, or glide behavior continues unchanged. Existing CPU Tails flight gates remain independent and unchanged. Movement owns jump-edge consumption; the controller returns whether transformation started and never mutates movement input latches.

| Character | Emerald gate | Shield gate | Invincibility gate | Fallback |
|---|---|---|---|---|
| Sonic | all Super, or all Chaos while not converted | Fire/Lightning/Bubble block; BASIC allowed | blocked | elemental move or insta-shield behavior |
| main/solo Tails | all Super only | none | allowed | existing flight path |
| CPU/sidekick Tails | never transforms | n/a | n/a | existing CPU flight gate |
| Knuckles | all Super, or all Chaos while not converted | none | allowed | glide |

## Rewind Design

`SuperStateController` will expose a compact immutable rewind record for its transformation phase and ring-drain counter. The S3K record will additionally carry palette state/frame/timer and transformation frames remaining. `PlayableSpriteController.RewindState` will include the controller record and restore it through the already-captured aggregate player controller state. The representation may be shared with S2 only if S2's full identity contract is implemented; a partial S2 scalar snapshot is forbidden.

Restore has two explicit phases:

1. Assign controller phase and scalar timing state.
2. Reconcile derived S3K player state from the restored phase: NORMAL uses normal physics/art/renderer and visible shield; TRANSFORMING uses normal physics/art with the captured forced transformation animation/control state; SUPER uses Super physics/art/renderer and hidden shield.

Reconciliation must not call activation/revert callbacks and therefore must not play audio, restart music, mutate rings, or spawn effects. Player-owned object-control bits, forced animation/frame state, shield state, and jump-input latches remain owned by the existing `PlayerRewindExtra`. Normal and Super animation/renderer/profile resources are immutable structural references, retained by the controller rather than serialized and not cleared in a way that prevents a backward-then-forward replay from restoring either presentation.

S2's `starsObject` raw gameplay reference will not be serialized. If S2 rewind support is included, the relationship must either be removed and rediscovered from `ObjectManager`, or captured/rebound through the rewind identity table; restore must produce exactly one live owned effect. Otherwise S2 controller rewind behavior remains outside this change and unchanged, while its automatic trigger is protected by regression tests.

No new uncaptured runtime latch will be introduced. The focused rewind round-trip test and the project rewind coverage guards will verify this contract.

## Test Design

Regression tests will cover:

- eligible S3K Sonic does not transform from controller `update()` alone;
- eligible Sonic transforms on the second press;
- normal ability-window boundaries reject `ySpeed < -0x400` and allow `ySpeed == -0x400`; underwater boundaries reject `ySpeed < -0x200` and allow `ySpeed == -0x200`; both reject an already-used double jump;
- elemental shields take priority while a BASIC shield does not block Sonic transformation;
- invincibility blocks Sonic but not otherwise-eligible Tails or Knuckles;
- a paused HUD timer blocks all three transformations;
- partial Super Emerald progression blocks Chaos-only Sonic/Knuckles transformation through the explicit conversion state;
- `emeraldsConverted` round-trips through `GameStateSnapshot` and explicit save export/import, represents converted progression with zero Super Emeralds, infers true for legacy saves containing Super Emeralds, and clears on reset/new game;
- elemental shields do not block eligible Tails or Knuckles;
- main Tails requires all Super Emeralds and otherwise enters flight;
- CPU Tails does not transform;
- Knuckles transforms before glide when eligible and glides otherwise;
- Sonic 2 retains its automatic airborne trigger;
- the native S2 capability route cannot invoke the S3K explicit activation path;
- S3K controller phase and timing state survive player rewind across NORMAL, TRANSFORMING, and SUPER boundaries without firing activation side effects;
- forward replay after restore continues palette, transformation, and ring-drain timing from the captured values, with physics/art matching the restored phase.

Focused Maven tests will run first, followed by rewind coverage guards and the relevant playable-sprite test set. A broader build will be run if the focused suite is clean.

## Scope

This change corrects activation timing, character eligibility, shield priority, and rewind continuity. It does not add missing Super/Hyper art, attacks, or unrelated character-form behavior, and it does not introduce zone-, route-, or frame-specific exceptions.
