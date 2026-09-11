# The `isOnScreen(<literal>)` deletion gates, re-classified under the two-halves rule

**Measured at** `1a5fb6f89`, 2026-08-21. **Classification only — nothing converted.**

The rule: *can you name the routine that computes the number, **and** the branch that selects
that routine for this object?* Both halves or the margin is invented. A literal is not the tell.

## Headline

| | count |
|---|---|
| deletion gates with a literal margin | **34** |
| **S3K — cannot be classified, see below** | **16** |
| classifiable (S1 4, S2 10, shared-tree S2 4) | **18** |
| **faithful** | **0** |
| **invented — all fail half 1** | **18** |

**Not one classifiable site passes.** And they do not fail on the *value* — they fail because
the cited routine computes a **different quantity**, or **no quantity at all**. Correcting
numbers was never going to fix any of them, which is the same conclusion the first sweep reached
by a different route, now established per site rather than in aggregate.

## The upward movers — cited, and pointing at a routine that computes something else

These are the rule-113 shapes: correct-looking, and invisible to any output check.

| site | margin | cites | what that routine actually computes |
|---|---|---|---|
| `ArrowProjectileInstance:107` | 480 | `MarkObjGone` | **640** (`s2.asm:30222`) — and the comment's own expression evaluates to 640 while calling it 480 |
| `BalkiryJetObjectInstance:79` | 64 | `Obj_DeleteBehindScreen` | **no number at all** — `bmi` on the coarse difference (`s2.asm:72983-72992`), a sign test that deletes only *behind* the camera |
| `HtzFireProjectileObjectInstance:96` | 128 | `Camera_Max_Y_pos` + `screen_height` | a **bottom-of-level Y** test — different quantity *and* different axis from a camera band |

`BalkiryJet` is the sharpest: half 2 **passes** — the ROM really does route Balkiry to
`Obj_DeleteBehindScreen` — while half 1 fails absolutely, because the routine contains no
constant to cite. The engine also deletes objects *ahead* of the camera, which that routine
never does.

## Four distinct ROM predicates, where the first sweep named two

1. **`out_of_range` / `MarkObjGone`** — coarse, unsigned, bound 640, X only. Modelled by
   `ObjectRangeOps`.
2. **The render flag** — `width_pixels` on X; on Y either `y_radius` or
   `BuildSprites_ApproxYCheck`'s 32, selected by `explicit_height`.
3. **`Obj_DeleteBehindScreen`** — coarse **sign test**, no constant, deletes only behind the
   camera (`s2.asm:72983-72992`). **Not modelled.**
4. **`Obj28_ChkDel`** — **player-relative**: `x_pos - MainCharacter x_pos`, a one-sided `$180`
   = 384 window, *then* the render flag (`s2.asm`, `Obj28_ChkDel`). **Not modelled**, and not
   camera-relative at all.

`HtzFireProjectile`'s bottom-of-level Y test is arguably a fifth. **`ObjectRangeOps` models one
of these**, so the audit's "add two primitives" plan understates the work.

## A correction to the first sweep: the render-flag group is far larger than 2

That sweep reported **2** render-flag sites. Among the classifiable 18 there are at least
**6**: `Sonic1BatbrainBadnikInstance:398` and `Sonic1FalseFloorInstance:583` both cite
`tst.b obRender(a0) / bpl DeleteObject`, and all four `EggPrisonAnimalInstance` gates reach the
flag — `Obj28_Main` opens with `_btst #render_flags.on_screen / _beq DeleteObject`, and
`Obj28_ChkDel` ends with the same test.

**Why the first count was wrong, since the failure is reusable:** it keyword-searched S2's
`render_flags` / `on_screen` spelling. S1 spells the same thing `obRender`, and a site whose
comment cites the *routine* rather than the *flag test* matches neither. A classification keyed
on one game's vocabulary silently under-reports every other game.

## The 16 S3K sites are blocked, not unclassified-by-omission

`skdisasm` has no `BuildSprites`, `ApproxYCheck` or `explicit_height` labels at all, so S3K's
culling geometry is a research task rather than a lookup. Classifying these by S2's rules would
be rule 113 committed deliberately. They are listed here so nobody re-derives the blocker:

`AizDrawBridge:390` · `AizMinibossBarrelShotChild:224` · `AizMinibossDebrisChild:66` ·
`CorkFloor:614` · `CutsceneKnucklesLbz1ThrownBomb:58` · `CutsceneKnucklesMhz1:116` ·
`HCZBreakableBar:670` · `HCZCGZFan:782` · `HCZLargeFan:136` · `Mgz2CapsuleAnimal:73` ·
`Mushmeanie:411` · `S3kBadnikProjectile:125` · `SnaleBlaster:702` ·
`Sonic3kCollapsingPlatform:520` · `Sparkle:410` · `StarPointer:276`

**47% of the population is behind one piece of research.** That is the single highest-leverage
item in this family: establishing S3K's culling geometry unblocks sixteen sites at once, more
than every classifiable conversion combined.

## Full verdicts

**Invented, uncited (fails both halves) — 9:** `Sonic1Cannonball:241` (256) ·
`Sonic1Orbinaut:408` (256) · `Aquis:227` (64) · `BombPrize:138` (64) · `CPZBossGunk:186` (32) ·
`EHZBossVehicleTop:58` (128) · `EHZBossWheel:195` (64) · `RexonHead:448` (64) ·
`RingPrize:130` (64)

**Invented, cites the render flag (fails half 1 — the flag is not a distance) — 6:**
`Sonic1Batbrain:398` (160) · `Sonic1FalseFloor:583` (48) · `EggPrisonAnimal:116/155/175/199` (64)

**Invented, cites a distance routine computing something else (fails half 1) — 3:**
`ArrowProjectile:107` · `BalkiryJet:79` · `HtzFireProjectile:96`

**Faithful — 0.**
