# Sonic 2 Special Stage Tails and HUD Design

## Goal

Match the original Sonic 2 special stage when Sonic has Tails as a sidekick: render Tails from the correct Obj10/Obj88 mappings and dynamic art, and render the original three-part team ring HUD.

## Disassembly reference

- `docs/s2disasm/s2.asm` `Obj10` initializes Tails with `Obj10_MapUnc_34B3E`, palette line 2, and the `ArtTile_ArtNem_SpecialTails` dynamic-art destination.
- `LoadSSTailsDynPLC` selects source groups beginning at pattern offsets `$183`, `$1C0`, `$264`, and `$29E` in `SSRAM_ArtNem_SpecialSonicAndTails`. It uses the reverse DPLC records shared with Obj09 to copy only the tiles required by the current mapping frame.
- `Obj88` renders Tails' two-tail appendage with its own mappings and source groups beginning at `$2AE`, `$2E3`, and `$31E`, loaded at `ArtTile_ArtNem_SpecialTails_Tails`.
- `Obj5E` selects `SSHUD_SonicMilesTotal` or `SSHUD_SonicTailsTotal` when `Player_mode` represents the team. Each layout contains three child sprites: Sonic, Tails/Miles, and total.

## Rendering design

Replace the renderer's Tails shortcut—Sonic mappings plus a fixed pattern offset—with explicit ROM-derived Tails body mappings. Mapping tile indices name dynamic VRAM destination slots, not positions in the decompressed source art. For every mapping frame, decode the corresponding reverse-DPLC record from `Obj09_MapRUnc_345FA` (body record `mapping_frame+$12`; appendage record `mapping_frame+$24`) into destination-to-source runs, then translate every mapped destination tile through those runs. The source address is relative to the selected `$183/$1C0/$264/$29E` body or `$2AE/$2E3/$31E` appendage group. The engine's virtual atlas replaces the actual DMA copy, but preserves its exact tile selection.

Model Obj88 as a separate appendage object owned by the special-stage Tails player. It copies the parent's animation, status, and position, but advances its own seven-frame `Ani_obj88` sequence with independent frame, duration, and previous-animation state. That state is captured and restored with the player rewind snapshot. Render it as an independent priority-sorted entry at body priority minus one, so overlap ordering with Sonic remains faithful. Its mappings, source groups, palette, and flip state remain separate from the body and cannot index Sonic's cached patterns.

Sonic continues using Obj09 mappings and its existing art path. Shared rendering mechanics may be extracted only where doing so avoids duplicating mapping-piece traversal; no unrelated special-stage rendering changes are in scope.

## HUD design

Sonic-only mode uses `SSHUD_Sonic` at object X `$D4`. Tails-only mode uses overseas `SSHUD_Tails` frame 2 at object X `$38`, with digits beginning at X `$9C`, Y `$20`. Team mode renders the Obj5E three-child layout in native H32 coordinates:

1. Sonic label and Sonic's `player.getRings()` value.
2. Tails label and Tails' `player.getRings()` value. The required World REV01 ROM takes the overseas `Graphics_Flags` path, so this scope renders `TAILS`; it does not invent a domestic/overseas setting from the unrelated NTSC/PAL timing configuration.
3. Total label and `objectManager.getRingsCollected()`.

The renderer will receive explicit player counts and team presence from the manager. In team mode it uses Obj87's exact H32 coordinates: Sonic digits begin at `$48`, Tails digits begin at `$E0`, and total digits use `$80` for one digit, `$7C/$84` for two, or `$78/$80/$88` for three, all at Y `$20`. Hundreds and tens are suppressed exactly as `loc_7480` and `loc_753E` specify. Obj5E uses object X `$80` with overseas frames 0, 2, and 3. These native coordinates pass through the renderer's established H32 viewport transform once; the current undocumented `-6` adjustment is removed.

## Testing

- A ROM-free mapping test proves Tails body frames come from Obj10 and that representative nonzero destination tile indices, including a DPLC run boundary, resolve through the frame-specific reverse-DPLC records to the expected ROM source tiles.
- A ROM-free appendage test proves Obj88 frames use the tail-art source groups and distinct destination/art identity, advance independently through the seven-frame scripts, and survive rewind restoration.
- A render-order test proves Obj88 participates at body priority minus one when Sonic and Tails overlap.
- A renderer recording test proves Sonic-only and Tails-only modes emit their character's original single counter, while team mode emits Sonic, Tails, and total counters with independent values and original layout anchors.
- Existing per-player ring and renderer determinism tests remain green.

## Non-goals

This change does not alter player physics, ring collection/debit rules, checkpoint requirements, special-stage traces, or non-Sonic-2 special stages.
