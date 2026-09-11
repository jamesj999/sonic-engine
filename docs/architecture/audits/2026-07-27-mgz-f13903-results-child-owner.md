# MGZ f13903 results-child owner audit — corrected

## Correction notice

The original version of this audit incorrectly described the grounded MGZ
results child as occupying a later native SST slot and therefore running on
the allocation pass. A bounded ROM execute trace disproved that claim.

At MGZ trace f13903, `Obj_EndSignResults` runs in slot 9 and
`AllocateObject` assigns `Obj_LevelResults` to slot 8. Because slot 8 has
already run, the child initializes on the next `Process_Sprites` pass at
f13904. HCZ has the same ordering: its sign is in slot 24, the results child
is assigned slot 8, and the child initializes on the following pass.

The one-entry results create/retirement catch-up is therefore not ROM-backed.
It is named `UNSUPPORTED_GROUNDED_COMPENSATION` in production so it cannot be
mistaken for native SST ownership. It remains isolated because removing it
without first identifying MGZ's real missing owner would regress the accepted
standalone MGZ frontier. A separate investigation must replace that
compensator with the actual native state owner.

## Verified routine ownership

`Obj_EndSignResults` does own Player 1's ending-pose write on its grounded
routine-6 entry. Publishing that pose immediately is accurate. The result
object's initialization is not part of that same pass:

- MGZ f13902: sign slot 9 expires `Obj_EndSignLanded`.
- MGZ f13903: sign slot 9 runs `Obj_EndSignResults`, writes the pose, and
  allocates results slot 8.
- MGZ f13904: results slot 8 enters `Obj_LevelResults`.
- HCZ f9760: sign slot 24 expires `Obj_EndSignLanded`.
- HCZ f9761: sign slot 24 runs `Obj_EndSignResults`, writes the pose, and
  allocates results slot 8.
- HCZ f9762: results slot 8 enters `Obj_LevelResults`.

These observations came from execute hooks on `Obj_EndSignLanded`
(`$838C2`), `Obj_EndSignResults` (`$83912`), `Set_PlayerEndingPose`
(`$869C6`), `AllocateObject` (`$1BAF2`), and `Obj_LevelResults`
(`$2DAE6`) against the locked-on ROM.

## Current verification

- MGZ standalone remains at f23561 `rings` (expected `0`, actual `1`).
- MGZ complete run remains at f28398 `rings` (expected `2`, actual `1`).
- The corrected HCZ control-entry timing restores its complete run to f10423
  `rings` (expected `149`, actual `0`).

No result-child timing change is made by this correction notice.
