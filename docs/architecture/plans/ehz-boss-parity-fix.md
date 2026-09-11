# EHZ Boss Parity Fix Plan (Sonic 2 REV01)

This plan tracks required changes to align the EHZ boss implementation and boss framework with the S2 disassembly.
Primary references: `docs/s2disasm/s2.asm` (Obj56) and `docs/s2disasm/mappings/sprite/obj56_*.asm`.

## Boss Framework
- [x] Route **BOSS** collision category through `TouchResponseAttackable` and apply player bounce when attacking.
- [x] Ensure boss collision uses **dynamic position** (not immutable spawn) for moving bosses.
- [x] Allow defeated bosses to keep running their own state machine (no early return).
- [x] Fix boss palette flash color 0xEEE -> RGB 255,255,255.
- [x] Implement Boss_LoadExplosion-style random offsets for defeat explosions.

## EHZ Boss Core (Obj56 Main)
- [x] Match initial repositioning to `0x2AF0, 0x02F8` after init; target X/Y remain `0x29D0, 0x041E`.
- [x] Use 8.8 velocity with 16.16 position math (`x_vel = -0x200`, etc.).
- [x] Sub0/Sub2/Sub4/Sub6/Sub8/SubA state logic match Obj56 code paths.
- [x] Correct boundary check (0x28A0/0x2B08) and flip logic.
- [x] Wheel Y accumulator handling matches `objoff_2E` semantics (use then clear).
- [x] Defeat flow: set Sub6, timers, spike separation flag, top anim to flying off, PLC capsule placeholder.
- [x] Use ObjCheckFloorDist-style floor snap in Sub6 defeat fall (no fixed Y clamp).
- [x] Flying-off sequence increments camera max X by 2 until 0x2AB0; delete when off-screen.
- [x] Spawn EggPrison at fixed boss arena location (using floor-based origin).

## EHZ Boss Components
### Propeller (Obj56 routine 4/C, Ani_obj56_a)
- [x] Use exact Ani_obj56_a scripts (frames + $FD/$FE behavior).
- [x] Landing: anim=1, timer=0x18, descend then delete at -0x10.
- [x] Reload after defeat: spawn propeller with anim=2, timer=0x10, rise then switch to normal.
- [x] Helicopter SFX every 32 frames while airborne.

### Ground Vehicle (Obj56 routine 6)
- [x] Camera min X gate (>= 0x28F0), approach to 0x29D0.
- [x] Follow parent only when active and not flying off; Y offset +8.
- [x] Render using palette 0 (ArtTile_ArtNem_EHZBoss,0,0).

### Wheels (Obj56 routine 8, Ani_obj56_b)
- [x] Sub0 move-to-start positions (0x29EC/0x29C4/0x29A4) with floor check.
- [x] Sub2 idle until parent active flag set.
- [x] Sub4 uses ObjectMoveAndFall + floor snap, sets y_vel=0x100, adds Y to parent when priority=2.
- [x] Sub6/Sub8 defeat bounce timing and x_vel inversion for non-foreground wheel.
- [x] Use Ani_obj56_b scripts (delay=1).

### Spike (Obj56 routine A, Ani_obj56_b)
- [x] Camera min X gate, move-to-start x=0x299A.
- [x] Attached: follow parent, y+0x10, x offset +/-0x36, collision flags 0x8B.
- [x] Separation logic matches loc_2F8AA (only at hitcount==1 and player in front).
- [x] Separated motion: constant +/-3 px/frame, no gravity.

### Vehicle Top (Obj56 routine E, Ani_obj56_c)
- [x] Use Ani_obj56_c scripts with correct $FD behavior.
- [x] Hit detection: anim=2 when invul timer == 0x1F.
- [x] Laughing: anim=3 when player is in ball and not in hit anim.
- [x] Flying off: anim=4 set by boss defeat routine.
- [x] Fix frame index offset (top frames start at sheet index 15).

## Art / Palette
- [x] Keep composite EHZ boss sheet mapping order (propeller 0-6, vehicle 7-14, top 15-21).
- [x] Apply palette override 0 for ground vehicle and vehicle top.

## Validation
- [ ] Compare in-engine behavior against disassembly for Obj56 Init/Sub0/Sub2/Sub4/Sub6/Sub8/SubA.
- [ ] Verify spike separation timing (hitcount==1) and direction.
- [ ] Verify defeat explosions cadence and random offset range (-0x20..0x1F).
