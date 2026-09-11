# CNZ f4826 cylinder recapture

## Finding

The standalone CNZ replay reached frame 4826 with Tails' animation and mapping
still owned by the preceding CPU pass (`$20` / `$A0`). Native instead finishes
the cylinder slot with animation `$00` and mapping `$55`.

The cylinder's rider slot and its `SolidObjectFull` standing bit are separate
pieces of state. An off-screen Player 2 solid pass can skip while the standing
bit remains set even though the cylinder's own rider slot releases. The first
subsequent on-screen cylinder pass must still consume that bit.

## Native evidence

- `Obj_CNZCylinder` calls the Player 2 `sub_324C0` path after the Player 2
  control/animation pass (`sonic3k.asm:67656-67672`).
- `Tails_FlySwim_Unknown` can first publish the complete
  `object_control=$81` CPU marker (`sonic3k.asm:26651-26653`).
- The inactive `sub_324C0` path tests only the cylinder standing bit. When it
  is set it clears speed, writes `object_control=$03`, selects animation zero,
  and dispatches `PlayerTwist_UpdateFrame`
  (`sonic3k.asm:67985-68012,68078-68100`).
- `sub_324C0` does not write `y_pos`; the later `SolidObjectFull` pass owns
  either a platform snap for a genuine overlap or clearing stale support.

A bounded BizHawk probe recorded 24 stage-gated events. At game-frame `$12DB`,
the CPU pass entered `loc_13D42` with animation `$20`, the cylinder entered
with its Player 2 standing bit set and `object_control=$81`, and the twist path
then observed `object_control=$03` with animation `$00`. Native retained
Tails' `$062C` Y position while the later solid path restored airborne status.
The probe output SHA-256 was
`a13d2e36dc95e64493d50d75d541dec11b13ed7afe943d140ecad0774e0918c0`;
the one-off probe script was removed after capture.

## Fix and verification

`CnzCylinderInstance` now carries a standing bit across exactly the next
cylinder dispatch when the previous `SolidObjectFull` pass skipped an
off-screen rider. The inactive rider path follows the ROM's standing-bit test
regardless of the preceding CPU `object_control` value. A stale on-screen
recapture does not manufacture a Y snap; the later solid phase restores
airborne status while leaving the cylinder's `$03` control ownership intact.

RED coverage
`firstOnscreenPassConsumesStandingBitPreservedByPriorOffscreenSolidSkip`
previously left the rider slot inactive and now verifies the native animation,
mapping, position, status, and control ownership. The standalone frontier
advances from f4826 `tails_animation_id` to f6680 `status_byte`; the canonical
comparison falls from 3,702 to 3,700 errors. No trace, route, zone, or visual
frame predicate was introduced.
