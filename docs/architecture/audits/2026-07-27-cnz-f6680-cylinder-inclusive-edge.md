# CNZ f6680 cylinder inclusive right edge

## Finding

The standalone CNZ replay reached frame 6680 with Sonic's position, velocity,
ground state, animation, and mapping matching native, but without native
`Status_Push`. Native retained `$20` through f6684; the engine reported `$00`
and subsequently advanced the walk mapping early.

At the divergence Sonic is grounded at `$15EB,$052C`, with
`x_vel=ground_vel=$000C`. The relevant cylinder is at `$15C0,$04FF` and calls
`SolidObjectFull` with `d1=$2B`. Sonic is therefore exactly on its right
collision boundary: `x_pos(a1)-x_pos(a0) == d1`.

## Native evidence

`Obj_CNZCylinder` calls `SolidObjectFull` with `d1=$2B`, `d2=$20`, and
`d3=$21` after updating its position (`sonic3k.asm:67656-67672`).
The new-contact path reaches `SolidObject_cont`, whose horizontal entry gate
doubles `d1` and rejects only when the translated relative X is higher
(`cmp.w d3,d0; bhi`). Equality remains live
(`sonic3k.asm:41383-41400`).

The side branch at equality has `d0=0`, so `loc_1E042` skips velocity stopping
and performs a zero position correction. Because Sonic is grounded,
`loc_1E06E` still sets both the cylinder pushing bit and player
`Status_Push` (`sonic3k.asm:41468-41501`). Thus the simultaneous native
outcomes are push set, `$000C` velocity retained, and no X correction.

The engine already models those side-contact effects in the shared solid
controller. The cylinder alone failed to expose the ROM's inclusive right
edge, so geometry rejected the contact before reaching them. No native probe
was needed because the canonical trace row plus the ROM helper's exact-edge
branch uniquely identify the owner and outcome.

## Fix and verification

`CnzCylinderInstance.usesInclusiveRightEdge()` now returns true with the ROM
gate citation. RED coverage places a grounded player at `cylinderX+$2B`,
moving away at `$000C`, and verifies push, unchanged velocity, and unchanged
position. It failed with `pushing=false` before the provider correction and
passes afterward.

The standalone frontier advances from f6680 `status_byte` to f10728
`player_mapping_frame`; canonical errors fall from 3,700 to 3,676. The five
known later CNZ scenario failures are unchanged, and standalone AIZ and MGZ
retain f8837 and f13903 respectively. The change uses object geometry only:
there is no trace, route, zone, frame, or final-status predicate.
