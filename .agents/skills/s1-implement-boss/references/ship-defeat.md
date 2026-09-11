# S1 Eggman ship defeat and escape

Applies to the GHZ/MZ/SYZ/SLZ/LZ family (`AbstractS1EggmanBossInstance`).
Verify the selected boss's routine; Final Zone is a different encounter.

- `B*_Defeated` sets the secondary routine and timer, then returns. Dispatch
  reads that routine next frame, so the first explosion countdown decrement
  follows the killing hit. The engine's touch pass precedes object update;
  inspect `defeatDeferralAppliesToThisBoss()` when preserving this edge.
  Timer values differ (GHZ `$B3`, SYZ `$B4`).
- `B*_ShipMain` ends in `DisplaySprite`, not `MarkObjGone`/`out_of_range`.
  The ship owns its lifetime and must survive offscreen escape until its
  camera-boundary work finishes. Two `BossMove` calls move it faster than the camera.
- Defeat does not clear `f_lockscreen`; Egg Prison Obj3E does. The engine's
  `currentBossId` models that lock. Clearing it on defeat changes the player's
  right-boundary behavior before the capsule.

Source entrypoints: `docs/s1disasm/_incObj/75, 76 Boss - SYZ Main and Blocks.asm`
(`B*_ShipMain`, `B*_Defeated`, explosion/escape routines), and the Egg Prison's
`clr.b (f_lockscreen).w`. Historical implementation status is not a contract;
inspect the current shared base and concrete boss.
