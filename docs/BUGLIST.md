# Bug List

Last updated: 2026-01-16

## Open Bugs

- [x] If a sound is playing when the level is switched, it gets stuck looping that part of the sound.
- [ ] If you're standing to the left of a monitor and the ground is tilted slightly towards it, Sonic fails to jump.
- [ ] Some spikes kill you from the side when you jump next to them while holding direction into them.
- [x] There's a small collision hole on the left side of the EHZ bridges. (Fixed: Bridge collision anchor now uses ROM-style x_pos-8 offset; slope sampling aligned to collision space.)
- [x] EHZ bridges cause you to move from rolling to running when you move over them. (Fixed: Terrain no longer forces air if solid contact exists; object landing preserves roll state.)
- [x] Some sound engine discrepancies (some instruments have volume issues, springs don't sound correct). (Fixed: Made KEY_ON conditional on !t.tieNext, same as KEY_OFF. Fixed PSG mix volume after fixing PSG noise volume)
- [x] Double-length spirals in EHZ don't work (Sonic only completes the first half then falls out).
- [x] Objects still have collision in debug movement mode. (Fixed: Debug mode now skips all physics, collision, and damage processing. Toggled via configurable keybind - default 'D' key)
- [x] Camera maximum height is not yet implemented. (Fixed: Added LevelEventManager for dynamic boundary updates with 2px/frame easing toward targets. Camera class now supports target-based boundaries matching ROM's Camera_Max_Y_pos_target system.)
- [ ] Some situations (ring loss on spikes in MCZ) result in rings being instantly recollected.
- [x] Finishing a special stage puts you at your last coordinates, instead of the coordinates of the last signpost (or, if none, fall back to act start position). (Fixed: CheckpointState now saves/restores camera position with checkpoint data, matching ROM's Obj79_SaveData/LoadData behavior)
- [x] Both special stage and end of act cards shouldn't start counting the score until a set delay. (Fixed: Added STATE_PRE_TALLY_DELAY with ROM-accurate $B4/180 frame delay)
- [x] Special stage results ending should fade to white (verify!)
- [x] When a Special stage ends, the star post still has the special stage entry stars above it, allowing you to re-enter the special stage, even though rings are reset to zero

## ROM-Accurate Investigation Plan

Each fix must be verified against the original `docs/s2disasm/` disassembly (REV01). Do not tune constants or approximate behavior.

| Bug | Disasm Routines to Examine | Key Verification |
|-----|---------------------------|------------------|
| Sound looping on level switch | `GameMode` transitions, `sndDriverInput`, Z80 driver reset/init | Match 68K→Z80 command stream timing; verify bus reset behavior matches ROM |
| Jump fails near monitor | `Sonic_Jump`, `SolidObject`, `ChkFloorEdge`, `AnglePos` | Compare per-frame ground flag, push status, and jump input timing order |
| Spikes side-kill | `objects/Spikes.asm`, `TouchResponse`, `HurtSonic` | Match hitbox dimensions + directional check (player Y vs spike top) |
| Bridge collision hole | `objects/EHZbridge.asm` segment loop bounds | Fixed: aligned bridge render/collision anchor to ROM x_pos-8 offset |
| Bridge cancels roll | Bridge collision handler + `Status` bits | Fixed: avoid terrain detaching when solid contact exists; preserve roll on object landing |
| Double spiral fails | `objects/Spiral.asm`, control lock, gravity disable flags | Track substate transitions, timer values, position clamps |
| Debug mode collision | `Debug_mode_flag` checks in collision dispatch | Match ROM's exact early-out conditions per interaction type |
| Instant ring recollect | `objects/RingLoss.asm`, spill routine, collect lockout | Spilled rings need N-frame inert period before becoming collectible |
| Special stage exit coords | Starpost save variables (`Saved_X/Y`), `GM_Special` exit | Distinguish starpost X/Y from entry X/Y; verify fallback logic |
| Score tally delay | Results screen routine, frame counter before tally | Find exact delay value (likely ~120 frames) |
| SS fade to white | `GM_Special` exit, palette fade routine | Verify fade direction (to white vs to black) |
| Camera max height | Camera boundary routines, level header limits | Fixed: LevelEventManager + Camera target-based easing |
| Sound discrepancies | FM operator levels, envelope handling, SFX priority | Compare against SMPSPlay reference per `docs/AudioParityPlan.md` |

## Verification Approach

1. **Lock revision**: Use REV01 ROM and matching disassembly build (`fixBugs=0`)
2. **Create repro scripts**: Emulator savestate + input movie for each bug
3. **Log RAM variables**: Track key state (player status bits, object routines, timers)
4. **Compare frame-by-frame**: ROM vs recreation until first divergence
5. **Translate exact logic**: Match condition ordering, sign handling, and early-outs
