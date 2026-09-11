# CNZ f10728 mapping owner

## Root cause

At native f10727 the earlier balloon slot launches Sonic, sets
`Status_InAir`, and clears `object_control`. The later CNZ cylinder slot still
sees its rider status byte set, so `sub_324C0` executes the held
position/twist-frame write. The later `SolidObjectFull` call clears the
cylinder standing status. The rider therefore cannot reach `loc_32604` until
the cylinder's f10728 slot. Before that slot, the ordinary f10728 player slot
reaches `Animate_Sonic` and publishes walk frame `$08`.

The engine's matching `release_external_air` path collapsed those two native
object passes into one cylinder update: it called `holdSlotPositionOnly` and
then immediately `clearSlotOnly`. Releasing the engine-only
`objectMappingFrameControl` latch in that same update fixed the visible
frontier but still retired the rider one owner dispatch too early.

The correction is narrow: after publishing the final twist frame, the
external-air path marks the rider for retirement and relinquishes cylinder
mapping ownership. The next real player animation publishes `$08`; only the
following cylinder dispatch retires the rider at the represented
`loc_32604` boundary. No direct `$08` assignment, trace state, frame
predicate, or zone predicate is required.

## Native evidence

The relevant balloon touch routine writes `y_vel=-$700`, sets
`Status_InAir`, and clears `object_control`
(`docs/skdisasm/sonic3k.asm:66809-66816`). The later cylinder rider pass sees
its standing byte still set and executes the held-position and
`PlayerTwist_UpdateFrame` path. Its later `SolidObjectFull` call clears
standing status. On the following object pass, the cylinder reaches
`loc_32604`, which clears only the rider byte
(`sonic3k.asm:68024-68025,68076-68083`).

The committed trace confirms the ordering without a new native probe. At
f10727 Sonic is airborne with `object_control=0` while the final twist
`mapping_frame=$59` remains visible. At f10728 the ordinary player pass keeps
`anim=$00` and publishes `mapping_frame=$08`. `Animate_Sonic` owns that next
write: the walk/run handler selects the current `anim_frame` mapping before
its timer update (`sonic3k.asm:24849-24879`).

## RED/GREEN

`externalAirLaunchRetainsRiderThroughFinalTwistThenRetiresAfterNextAnimatePass`
uses a real cylinder and playable animation manager. The revised RED preserved
the release-row `$59` but failed because the rider was already inactive. The
cylinder now keeps a typed pending-retirement state after its last
position/twist publication, clears only `objectMappingFrameControl`, lets the
ordinary animation script publish `$08`, and discards the rider on its next
update.

The complete 36-test cylinder suite, 37-test playable-animation suite, and
rewind coverage guard pass.

## Replay verification

Commands used the discovered locked-on ROM, worktree-local
`target/trace-f10728-tmp`, one Surefire fork, and
`-Dsurefire.argLine='-Xshare:off -Xmx6g'`.

- Frontier-only standalone CNZ advances from f10728
  `player_mapping_frame` (expected `$08`, actual `$59`) to f14157 `tails_x`
  (expected `$2EF8`, actual `$31F0`) with ten bounded errors.
- The canonical comparison falls from 3,676 to 3,675 errors and has the same
  f14157 first divergence.
- The complete 26-test CNZ scenario class has the expected comparison failure
  plus the five existing later scenario gaps at f15194, f15569, f17824
  (two assertions), and f20584; there are no errors or skips.
- Explicit standalone canaries retain AIZ f8837 `rings` and MGZ f13903
  `player_animation_id`.

The excluded route was not inspected, executed, modified, or used for target
selection.
