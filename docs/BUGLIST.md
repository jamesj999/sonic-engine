# Bug List

Last updated: 2026-01-22

## Open Bugs

- [ ] Buzzer badnik shoots projectile from wrong location (under body, instead of tail tip)
- [ ] Air bubble collection sound is incorrect - appears to be an audio engine bug
- [ ] ARZ: Sonic can't jump high enough to break rising pillars
- [ ] CPZ: H-flipped staircase platforms do not activate when Sonic walks on them
- [ ] CPZ: Walking down staircase platforms as they move causes collision issues (Sonic teleports down instead of moving smoothly)
- [ ] CPZ: Sideways platforms and rotating platforms have incorrect spacing/timing - not spaced out when loaded (likely shared timer issue)
- [ ] ARZ: Arrows disappear immediately when going off-screen (should persist longer like in ROM)
- [ ] ARZ: Pillars reset immediately when going off-screen (verify if this matches ROM behavior)
- [ ] ARZ: Grounder badnik cannot move from spawn location

## ROM-Accurate Investigation Plan

Each fix must be verified against the original `docs/s2disasm/` disassembly (REV01). Do not tune constants or approximate behavior.

| Bug | Disasm Routines to Examine | Key Verification |
|-----|---------------------------|------------------|
| Buzzer projectile spawn | `objects/Buzzer.asm`, projectile spawn offsets | Match X/Y offset from parent object center to projectile spawn point |
| Air bubble sound | `objects/Bubble Generator.asm`, SFX trigger, Z80 driver | Compare SFX ID and any special handling for breath restoration sound |
| ARZ pillars jump height | `objects/ARZ Rising Pillar.asm`, hitbox positioning, player jump velocity | Check pillar Y collision offset vs Sonic's max jump height |
| CPZ staircase H-flip | `objects/CPZ Staircase.asm`, flip flag handling, activation trigger | Verify H-flip flag is checked when determining step collision |
| CPZ staircase collision | `objects/CPZ Staircase.asm`, `SolidObject`, movement interpolation | Compare Y position updates during platform descent |
| CPZ platform timing | `objects/Sideways Platform.asm`, `objects/Rotating Platform.asm`, shared timers | Check `objoff_*` timer init, verify if linked to global frame counter |
| ARZ arrow persistence | `objects/ARZ Arrow Shooter.asm`, arrow child objects, despawn logic | Match off-screen distance threshold before removal |
| ARZ pillar reset | `objects/ARZ Rising Pillar.asm`, respawn/reset conditions | Check if pillar state persists via object memory or respawns fresh |
| Grounder movement | `objects/Grounder.asm`, patrol/chase AI, terrain detection | Verify movement triggers, wall collision, player detection range |

## Verification Approach

1. **Lock revision**: Use REV01 ROM and matching disassembly build (`fixBugs=0`)
2. **Create repro scripts**: Emulator savestate + input movie for each bug
3. **Log RAM variables**: Track key state (player status bits, object routines, timers)
4. **Compare frame-by-frame**: ROM vs recreation until first divergence
5. **Translate exact logic**: Match condition ordering, sign handling, and early-outs
