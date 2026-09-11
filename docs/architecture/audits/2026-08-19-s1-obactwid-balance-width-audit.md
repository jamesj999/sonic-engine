# S1 `obActWid` vs engine balance width — audit of every Sonic 1 `SolidObjectProvider`

**Date:** 2026-08-19. **Base:** `bdbcc274c`. **Scope:** the 42 classes under
`src/main/java/com/openggf/game/sonic1/objects/` that implement
`SolidObjectProvider`, checked against the `obActWid` byte their ROM object
writes.

**Nothing here is measured against a trace.** Every entry is a static
ROM-versus-engine comparison. Reachability is noted where it is obvious and
flagged where it is not. This is a list of candidates for the next round, not a
list of confirmed live defects — the one confirmed instance (Obj30, commit
`d7422d98f`) was found by a trace, and these were found by reading.

## What `obActWid` is, and why one byte matters twice

`_Constants.asm:230` names it *action width*; the per-object comments call it
*sprite display width*. Neither is the whole story, and it is never the rendered
sprite extent — the mappings own that. Three consumers read it:

- **`BuildSprites`' horizontal on-screen cull.** `d1 = obX - cameraX ± obActWid`
  tested against 0 and 320 (`docs/s1disasm/_inc/BuildSprites.asm:49-58`). This is
  the engine's `getOnScreenHalfWidth()`.
- **`Sonic_Balance`.** `d1 = obActWid(a1) + obX(a0) - obX(a1)`, balancing when
  `d1 < 4` or `d1 >= 2*obActWid - 4` (`_incObj/01 Sonic.asm:423-431`). This is
  `getBalanceWidthPixels()`, which defaults to the on-screen width.
- **Many objects' own solidity width**, passed to `PlatformObject`/`SolidObject`
  either raw or after `addi.w #sonic_solid_width,d1` (`$B`).

So a wrong `obActWid` is wrong in up to three places at once, and the engine's
`getBalanceWidthPixels()` default of 16 is only correct for objects whose ROM
byte happens to be `#32/2`.

## Two distinct failure modes

**(A) Full-solid objects fall back to the shared 16.** `getBalanceWidthPixels()`
returns `getOnScreenHalfWidth()` = 16 unless overridden. Every full-solid S1
object whose ROM `obActWid` is not 16 is wrong by the difference.

**(B) Top-solid objects fall back to `params.halfWidth()`, which is not always
`obActWid`.** The default assumes a `PlatformObject` caller passes `obActWid`
straight through as `d1`. Most do. **The two collapsing objects do not**, and
they are the more interesting half of this audit.

## Verified mismatches

`d1` column is the ROM's `SolidObject`/`PlatformObject` argument, shown to
demonstrate that the engine's *collision* width is already correct and only the
balance width is not.

| Object | ROM `obActWid` | ROM solid `d1` | engine balance width | delta |
|---|---|---|---|---|
| Obj1A Collapsing Ledge | **100** (`#200/2`, `FixBugs=0`) | `SlopeObject` 48 | 48 | −52 |
| Obj53 Collapsing Floor | **68** (`#136/2`) | `PlatformObject` 32 | 32 | −36 |
| Obj69 SBZ Trapdoor | **128** (`#256/2`, `FixBugs=0`) | 64+`$B` | 16 | −112 |
| Obj4E MZ Lava Wall | 80 | 32+`$B` | 16 | −64 |
| Obj33 Push Block (4x1) | 64 | `obActWid`+`$B` | 16 | −48 |
| Obj36 Spikes (subtype `$4x`) | 64 | `obActWid`+`$B` | 16 | −48 |
| Obj66 SBZ Rotating Junction | 48 parent / 56 child | 37+`$B` | 16 | −32 / −40 |
| Obj36 Spikes (subtype `$3x`) | 28 | `obActWid`+`$B` | 16 | −12 |
| Obj0C LZ Flapping Door | 40 | 8+`$B` | 16 | −24 |
| Obj36 Spikes (subtype `$0x`) | 20 | `obActWid`+`$B` | 16 | −4 |
| Obj3B GHZ Purple Rock | **19** (`#38/2`, `FixBugs=0`) | 16+`$B` | 16 | −3 |
| Obj26 Monitor | **15** (`#30/2`) | 15+`$B` = `$1A` | 16 | +1 |
| Obj2A SBZ Small Door | 8 | 6+`$B` | 16 | +8 |
| Obj44 GHZ Edge Wall | 8 | 8+`$B` = `$13` | 16 | +8 |
| Obj36 Spikes (subtype `$2x`) | 4 | `obActWid`+`$B` | 16 | +12 |

Four of these are on a `FixBugs` conditional and the value above is the shipped
`FixBugs = 0` branch — the wider, "wrong", accurate one. Obj1A's own comment
argues 200 pixels is too big and "could cause wrapping issues"; that is the
branch the traces record.

## Notable, because it is the easy one to get wrong

**The ROM byte is already in the engine for several of these.** `Sonic1Spike`
holds `actWidth` and builds its solid params as `actWidth + 0x0B`;
`Sonic1PushBlock` does the same with `activeWidth`; `Sonic1Monitor` hardcodes
`0x1A`, which *is* `15 + $B`. In each case the engine has derived the correct
collision width from the correct `obActWid` and then not handed that same value
to the balance test. These are one-line fixes with the value already local.

**Obj26 Monitor is the highest-traffic entry** and the smallest delta. The player
stands on monitors constantly, and `#30/2` = 15 is one pixel off the default, so
its balance window is shifted by one pixel at both edges.

## Checked and correct

Obj18 Platforms (32), Obj5A Circling Platform (24), Obj6C Vanishing Platform
(16), Obj63 LZ Conveyor (16), Obj5E Seesaw (48), Obj15 Swinging Platform,
Obj52 Moving Blocks and Obj33's 1x1 subtype all pass `obActWid` to the platform
helper unchanged, so the top-solid default resolves to the ROM byte. Obj32
Button, Obj3C Smashable Wall, Obj46 MZ Bricks, Obj51 Smashable Green Block and
Obj6F Spin Platform Conveyor are all `#32/2` = 16, which the shared default
happens to match. Obj56, Obj61, Obj59, Obj2F, Obj70, Obj6B, Obj31, Obj30 and the
FZ cylinder already override.

## Not established

- **No trace evidence for any row above.** Balance requires the player grounded,
  still, and `Status_OnObj` on that object; I did not establish that any
  committed fixture reaches that state on any of them.
- **Reachability varies and is not assessed.** Standing still on a spike, on the
  lava wall, or on the rotating junction is not obviously reachable in normal
  play even where it is mechanically possible.
- **The render-cull half of each mismatch is not assessed at all.** Where the
  ROM byte differs, `BuildSprites` culls at a different X than the engine does,
  which is a second divergence per row and would need its own measurement.
- Obj71 Invisible Barrier and Obj3E Prison Capsule derive `obActWid` from
  subtype or a table; I confirmed the engine models the collision width from the
  same source but did not enumerate per-subtype values.

## Correction, same day: the fallback fails for four, not two

The two classes left unverified above were chased when the guard was written,
and **both are mismatches**, so the top-solid fallback's premise holds for seven
S1 objects and fails for four rather than two.

- **Obj11 GHZ Bridge** — `Bri_Main` writes `#256/2` = **128** on the shipped
  `FixBugs = 0` branch, the listing calling it "way too large, causing the bridge
  to potentially screen-wrap… likely forgotten when the bridge was turned into
  individual 16px log objects" (`11 GHZ Bridge.asm:32-39`). `Bri_SolidObject`
  passes `bridge_children*8 + 8` (`:122-126`) and the engine models
  `logCount * 8`. The player stands on the parent; the log children take `#16/2`
  = 8 at `:94` and are not the stood-on object.
- **Obj83 SBZ2 False Floor** — `FFloor_Solid` stores the remaining half-width
  `d0` into `obActWid` and passes `d1 = sonic_solid_width + d0` to `SolidObject`
  (`82, 83 SBZ Eggman Cutscene and Crumbling Floor.asm:265-280`). The engine's
  solid params are correct at `0x0B + currentHalfWidth`, but `isTopSolidOnly()`
  is true, so the fallback hands balance `d0 + $B` where the ROM wants `d0` —
  and the width shrinks as the floor breaks, so it is a moving target.

Both are now fixed — Obj11 at a constant 128, Obj83 tracking its live
`currentHalfWidth` — so `TestS1BalanceWidthRomParityGuard` lands green with all
four of the fallback's failures closed and no class silently absent.

## Recommendation

Do not blanket-fix. Each row needs its ROM byte read at its own site, a decision
on whether the byte is the object's on-screen width too (supply it at
`getOnScreenHalfWidth()`) or only its balance width (override
`getBalanceWidthPixels()`), and a full `-Ptrace-replay` set-diff. The two
collapsing objects are the most valuable to take first: they are the ones that
falsify the current default rather than merely fall through it, and any fix
there should also revisit whether `getBalanceWidthPixels()`'s top-solid fallback
should exist at all.

## Reachability round, 2026-08-20

The audit's own "Not established" section said reachability was never assessed.
This round assessed it for the fifteen entries the guard carried as
`RECORDED_UNASSESSED`. **Fifteen, not nine** — that number in the brief for this
round was wrong, and the extra six are mostly boss children rather than level
objects.

Reachability here means: can the player be grounded, standing still, with the
object recorded as his stood-on object? That is what `Sonic_Balance` needs
(`_incObj/01 Sonic.asm:423`), and it is a stronger condition than the object
merely being solid.

### Reachable, and fixed this round

Each is its own commit with its own assertion; none is a measured constant.

| Object | ROM `obActWid` | site | why reachable |
|---|---|---|---|
| Obj3B GHZ Purple Rock | 19 (`#38/2`, `FixBugs=0`) | `getOnScreenHalfWidth()` | `Rock_Solid` `SolidObject` with stood-on `d3`; 25 placements across ghz1/ghz2/ghz3 |
| Obj69 SBZ Trapdoor | 128 (`#256/2`, `FixBugs=0`) | `getOnScreenHalfWidth()` | solid whenever frame 0 shows; standing on a closed trapdoor is the object's purpose |
| Obj41 sideways Spring | 8 (`#16/2`) | `getOnScreenHalfWidth()` | `Spring_LR` `SolidObject` `d3 = #30/2`; sideways springs across GHZ/SLZ/SYZ |
| Obj71 Invisible Barrier | `((sub & $F0) + $10) >> 1` | `getOnScreenHalfWidth()` | `SolidObject_NoRenderChk`; the object exists to be walked on |
| Obj33 Push Block 4x1 | 64 (`PushB_Var`) | `getOnScreenHalfWidth()` | `PushB_Action` `SolidObject` with stood-on `d3`; mz2 subtype `$81` |

All five are full-solid, so the `getOnScreenHalfWidth()` siting is the ordinary
one and `getBalanceWidthPixels()` inherits it; none needed the top-solid
interception. Obj69 and Obj41 carry the byte per variant off a subtype bit the
class already read, so neither adds a discriminator.

### Assessed and unreachable — recorded, deliberately not fixed

New guard disposition `RECORDED_UNREACHABLE`, distinct from "nobody looked".

- **Obj44 GHZ Edge Wall** (`obActWid` 8). `Edge_Solid` calls
  `EdgeWall_SolidWall`, not `SolidObject` (`44 GHZ Edge Walls.asm:35`;
  `sub SolidWall.asm:14-67`). That routine sets only the pushing bits and a
  ceiling stop. It never sets `Status_OnObj` and never records a stood-on
  object, so the balance test cannot select an edge wall at any width. This is
  the strongest ruling in the round: it is a property of the routine, not of any
  level's geometry.
- **Obj3E Prison Switch** (`obActWid` 12). `Pri_Switch` calls `SolidObject` and
  then, on the same frame `obSolid` comes back set, clears the player's
  `Status_OnObj`, sets the in-air bit, clears `obSolid` and locks the controls
  (`3E Prison Capsule.asm:88-109`). The standing state never survives into a
  player frame.
- **Obj36 Spikes** (`Spikes_Config` 20/16/4/28/64/16). The only subtypes the
  player can stand still on are the sideways ones, `$1x` and `$5x` — and their
  configured width is `#32/2` = 16, which the shared default already matches.
  Every subtype with a different width is upright, and on the shipped
  `FixBugs = 0` branch `Spikes_Upright` reads `btst #3,obStatus` and branches
  straight to `Spikes_Hurt` (`36 Spikes.asm:89-121`), damaging the player every
  frame instead of letting him idle. So the divergent widths and the standable
  subtypes are disjoint sets. **Caveat, and it is a real one:** `Spikes_Hurt`
  begins `tst.b (v_invinc).w`, so an invincible player does stand still on a
  wide upright spike. No level was checked for an invincibility monitor within
  reach of one. If someone wants this closed rather than caveated, that is the
  check to run.

### No divergence at all

- **Obj76 SYZ Boss Block** writes `move.b #32/2,obActWid(a1)`
  (`75, 76 Boss - SYZ Main and Blocks.asm:756`) — 16, the shared default. It was
  never a candidate; it is now `DEFAULT_IS_ROM_CORRECT`.

### Still undetermined, and what would settle each

Six remain `RECORDED_UNASSESSED`. All are solid with a stood-on `d3`, so the
question is geometric or lifecycle, not mechanical.

- **Obj4E MZ Lava Wall** (80). `LWall_Solid` is a real `SolidObject` with a
  stood-on `d3` (`4E MZ Wall of Lava.asm:77-87`), but the children carry
  `col_128x64|col_hurt` (`:36`). Settle by establishing whether the *parent*
  slot — the stood-on one — carries a hurt collision type, and whether mz2's one
  placement is ever approached from above.
- **Obj0C LZ Flapping Door** (40) and **Obj2A SBZ Small Door** (8). Both are
  solid only while closed and only from one side, with a 64-tall solid box in a
  corridor. Standing on the *top* of the box is mechanically expressible; whether
  the level geometry leaves headroom above either is the open question. Settle by
  loading lz2/lz3/sbz3 and sbz1/sbz2 headless and probing for floor above each
  placement.
- **Obj66 SBZ Rotating Junction** (48 parent). The 56-wide child is routine 4,
  display-only, so only the parent can be stood on. Settle the same way, on
  sbz1's two placements.
- **FZPlasmaLauncher** and **Sonic1FZBossInstance**. Both citations needed
  correcting: the launcher's `:781` is `EggmanCylinder_Init`'s write for a
  different object, and the launcher's own routine
  (`BossPlasma_Collision`, `:1022-1027`) never writes `obActWid` at all. Eggman's
  `:357,370` are `BossFinal_Eggman_Fall`'s post-defeat escape widths, not his
  combat ones, and REV00 writes `obWidth` there instead of `obActWid`. For both,
  the open question is what the slot's `obActWid` actually holds during the phase
  the player can stand on it; settle by tracing slot provenance from the FZ
  controller's child creation rather than by reading a width off a nearby line.

### What this round did not establish

- **No trace evidence for any of the five fixes.** They are ROM-versus-engine
  corrections with unit assertions, exactly as the original audit's rows were.
  The full-suite set-diff for them is recorded in the round's report, not here.
- **The render-cull half is still unassessed**, as the original audit said. Four
  of the five fixes move `getOnScreenHalfWidth()`, which is the cull consumer, so
  they change it deliberately and in the ROM's direction — but no measurement
  isolated the cull from the balance.

## Round two, 2026-08-20: the six remaining rows

All six are now settled except one. `RECORDED_UNASSESSED` is down from fifteen to
a single entry.

### Reachable, and fixed

| Object | ROM `obActWid` | how settled |
|---|---|---|
| Obj85 FZ Eggman | 32 combat (48 defeat fall) | slot provenance |
| Obj86 FZ Plasma Launcher | **0** | slot provenance |
| Obj66 SBZ Rotating Junction | 48 (parent) | headless drop probe |
| Obj2A SBZ Small Door | 8 | headless drop probe |

**The FZ pair came from tracing slot provenance, and both old citations were
wrong.** `BossFinal_Main` walks `BossFinal_ObjData2` with `a1` seeded as
`movea.l a0,a1` — the same parent-is-child-zero shape as the MZ glass pillar and
the MZ lava wall — so row 0's `#64/2` = 32 lands in Eggman's own slot
(`:56-58,96-101`). The previously cited `:357,370` are `BossFinal_Eggman_Fall`'s
*post-defeat escape* widths. Both sites are `Revision` conditionals rather than
`FixBugs` ones: REV00 writes `obWidth`.

Only the combat 32 is balance-observable. Reaching `Eggman_Fall` requires the
boss defeated, and `Sonic ReactToItem.asm:268` sets `obStatus` bit 7 on a
defeated boss — which `Sonic_Balance` tests and branches on *before* it reads the
width (`01 Sonic.asm:418-420`). Worth remembering generally: **a defeated boss is
exempt from the balance test entirely**, so no boss's post-defeat width is ever
balance-observable.

**The plasma launcher's byte is zero, and that is the ROM's omission, not the
port's.** `BossPlasma_Main` writes `move.b #16/2,obWidth(a0)` where it meant
`obActWid` (`:990-1001`) — the same fumble the listing flags for the cylinders at
`:776-778`, except the cylinders gained an `obActWid` write in REV01 and this one
never did, in either revision. Nothing else writes it, and `DeleteObject` zeroes
all `$40` bytes of a slot before `FindFreeObj` can hand it out
(`sub DeleteObject.asm:10-19`), so it stays 0 for the object's whole life.

Zero is not inert, and this is the interesting part. `Sonic_Balance` forms
`d1 = obActWid + dx` and `d2 = 2*obActWid - 4`, balancing when `d1 < 4` or
`d1 >= d2`. At `obActWid = 0` those become `dx < 4` and `dx >= -4`, which between
them leave no gap — **the ROM balances the entire time the player stands on the
launcher**, left-facing inboard of `dx = 4` and right-facing beyond it. A width
of zero is not "no balance window", it is a total one.

**Obj2A is the audit's first row where the ROM byte is smaller than the shared
default**, so the inherited 16 made the window *wider* than the ROM's rather than
narrower — suppressing balance across `4 <= |dx| < 12`. Every earlier fix
widened; this one narrows. Worth stating because "the default is too small" was
becoming an unexamined assumption.

### Assessed and unreachable

**Obj4E MZ Lava Wall** joins `RECORDED_UNREACHABLE`. `LWall_Main` seeds `a1` with
`movea.l a0,a1`, so the parent's own slot takes the whole `.make` block — both
`obActWid = #160/2` = 80 **and** `obColType = col_128x64|col_hurt` (`:22-37`).
The parent is the stood-on slot (`LWall_Solid`, routine 2), so standing on the
lava wall is standing on a hurt object.

The boxes genuinely overlap rather than merely abut, which is what makes this
decisive rather than suggestive. `SolidObject` seats the player at
`objY - d3 - y_radius` = `objY - 44`. `ReactToItem` builds his box from a top
edge of `y - (height - 3)` with height `2*(height - 3)`, i.e. `objY-60 .. objY-28`
(`Sonic ReactToItem.asm:14-38`). `col_128x64` is a **64x32 extent** — the macro
stores half-width and half-height (`:76-79,100`) — spanning `objY-32 .. objY+32`.
Four pixels of overlap, every frame. Same `v_invinc` caveat as the spikes.

### Still undetermined: one

**Obj0C LZ Flapping Door** (40). Two probe attempts failed for reasons that are
about the probe, not the level, and I would rather leave it open than report a
negative the method cannot support:

- The lz2 and lz3 placements never spawn. S1 loads objects from an X-ordered
  cursor that only advances as the camera moves right, so a teleported camera
  skips them; walking the camera forward in 128px steps did not fix it either,
  so something further gates LZ object loading in this harness.
- The sbz3 placement spawns but the player falls through. That is at least
  partly the probe's fault: `Flap_Animate` only makes the door solid while
  `obFrame == 0` **and** `sonic_x < door_x` (`0C LZ Flapping Door.asm:47-51`), and
  the door toggles open/closed on a `subtype * 60` frame timer, so a 120-frame
  observation window at subtype 2 can miss the closed phase entirely. Offsetting
  the player 8 and 16px left did not change the outcome.

What would settle it: drive the probe from the door's own state rather than a
fixed window — wait for `obFrame == 0`, place the player left of `door_x`, and
only then drop him; and establish why LZ placements do not load before trusting
any LZ result.

### Method note

The drop probe is positive-evidence-only and was not committed. It shows a top
surface is standable where the player is placed; it does not show a route reaches
that spot, and it cannot spawn what the camera jumps past. Read as "reachable"
its results are sound. Read as "unreachable" they would not be — which is exactly
why Obj4E was settled from the ROM instead.
