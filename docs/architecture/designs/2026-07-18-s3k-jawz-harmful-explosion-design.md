# S3K Jawz Harmful Explosion Design

## Goal

Restore Jawz's full Sonic 3 & Knuckles collision behavior in Hydrocity Act 2. When a vulnerable player touches Jawz, Jawz must become the short-lived HCZ explosion used by the original game. The explosion must hurt players during its active frames, render and animate like the ROM object, expire at the ROM-defined point, and remain deterministic across rewind.

## ROM Reference

`Obj_Jawz` records the touching native player in `collision_property`, then calls `Check_PlayerAttack`. An attacking player follows `Jawz_Defeated`. A vulnerable player instead creates `HCZEndBoss_ExplosionChild` at Jawz's position and deletes Jawz.

The child runs `HCZEndBossExplosion_Init` / `HCZEndBossExplosion_Main`:

- object data uses standard explosion mappings and `$8B` collision flags;
- raw animation is `7, 0, 0, 1, 2, 3, 4, $F4`;
- collision response is registered only while `mapping_frame < 3`;
- the object deletes itself when the animation ends.

Relevant disassembly is in `docs/skdisasm/sonic3k.asm` at `Obj_Jawz`, `HCZEndBossExplosion_Init`, `HCZEndBossExplosion_Main`, and `HCZEndBossExplosion_ObjData`.

## Architecture

Add a dedicated HCZ harmful-explosion object rather than changing the shared cosmetic explosion class. The object will use the existing standard explosion renderer, implement the normal hurt touch-response contract, and own only scalar animation state. It will use an engine-supported dynamic spawn/recreation contract so rewind can recreate it without an object-specific codec.

On the vulnerable-player branch, `JawzBadnikInstance` will spawn the explosion through `spawnChild(...)` at Jawz's ROM centre coordinates and then use the shared lifetime operation to delete Jawz. It will not call the player's damage API directly. This preserves the ROM's object allocation, collision timing, multi-player participation, shield/invulnerability handling, and collision-source position through the existing touch-response pipeline.

The attacking-player branch remains unchanged: Jawz is defeated through the badnik path and applies the existing enemy-defeat bounce.

## Behavior and Timing

The explosion will:

1. appear at the deleted Jawz object's centre position;
2. render the standard S3K explosion frames with ROM priority and dimensions;
3. return collision flags `$8B` only for mapping frames 0, 1, and 2;
4. expose no collision from frame 3 onward;
5. advance with the ROM raw-animation delay and repeated initial frame;
6. delete itself at the animation terminator.

Because damage remains collision-driven, a vulnerable player overlapping the spawned explosion is harmed according to the engine's normal S3K damage rules. Invulnerable, hurt, dead, or otherwise protected players retain the behavior provided by that shared path.

## Rewind

All gameplay-bearing child state will be scalar: animation timer, animation cursor/frame, and lifecycle state. Spawn position will be captured through the supported dynamic-spawn recreation mechanism. Renderer references remain derived structural state. No new rewind baseline exception or transient annotation will be added for gameplay state.

Focused rewind coverage will verify that the child is recreatable and that its active collision/animation state survives capture and restore through the generic object rewind path. Existing rewind coverage guards must remain green.

## Tests

Tests will be written before production changes and observed failing for the missing behavior. Coverage will include:

- vulnerable-player collision deletes Jawz and creates exactly one harmful explosion child at the same centre position;
- the child reports `$8B` collision during frames 0-2 and no collision from frame 3 onward;
- the child follows the ROM animation timing and self-deletes at the terminator;
- overlap with the active child harms a vulnerable player through the touch-response controller;
- an attacking player still defeats Jawz without spawning the harmful explosion;
- generic rewind capture/recreation retains the child's gameplay state;
- existing Jawz, object-touch, rewind coverage, and S3K object tests remain green.

## Scope

This change is limited to the Jawz vulnerable-contact transformation and the reusable HCZ harmful-explosion child it requires. It does not change generic explosion behavior, unrelated HCZ boss sequencing, or the shared damage rules.
